
AbstractPlayer : AbstractFunction  { 

	var <path,name,<>dirty=true; 
	
	var <synth,<group,<server,<patchOut,<>readyForPlay = false,<>playIsStarting = false,
		 <status, defName;
	var serverDeathWatcher; // should be moved to an object only owned by top player
		
	play { arg agroup,atTime,bus;
		var bundle,timeOfRequest,sendf;
		
		if(this.isPlaying,{ ^this });
		timeOfRequest = Main.elapsedTime;
		
		if(bus.notNil,{ 
			bus = bus.asBus;
			if(agroup.isNil,{
				server = bus.server;
				group = server.asGroup;
			},{	
				group = agroup.asGroup;
				server = group.server;
			})
		},{
			group = agroup.asGroup;
			server = group.server;
			// leave bus nil
		});
		
		bundle = CXBundle.new;

		if(readyForPlay,{
			this.makePatchOut(group,false,bus,bundle);
			this.spawnToBundle(bundle);
			//this.stateNotificationsToBundle(bundle);
			sendf = {	 bundle.sendAtTime(this.server,atTime,timeOfRequest); };
		},{
			sendf = {
				Routine({ //delay till prepared
					(this.prepareForPlay(group,false,bus) / 7.0).wait;
					//this.stateNotificationsToBundle(bundle);
					this.spawn(atTime,timeOfRequest);
				}).play(AppClock)
			};
		});
		if(server.serverRunning.not,{
			server.startAliveThread(0.1,0.4);
			server.waitForBoot({
				if(server.dumpMode != 0,{ 
					server.stopAliveThread;
				});
				InstrSynthDef.clearCache(server);
				if(server.isLocal,{
					InstrSynthDef.loadCacheFromDir(server);
				});
				sendf.value;
				nil
			});
		},sendf);
		CmdPeriod.add(this);
		// this gets removed in stopToBundle
		serverDeathWatcher = Updater(server,{ arg s, message;
			if(message == \serverRunning and: {s.serverRunning.not},{
				this.cmdPeriod; // stop everything, she's dead
			});
		});
	}
	prepareForPlay { arg agroup,private = false,bus;
		var bundle;
		bundle = CXBundle.new;
		this.prepareToBundle(agroup,bundle,private,bus);
		// group, server is now set
		//this.makePatchOut(group,private,bus,bundle);
		^bundle.clumpedSendNow(group.server)
	}
	prepareToBundle { arg agroup,bundle,private = false, bus;
		group = agroup.asGroup;
		server = group.server;
		status = \isPreparing;
		bundle.addFunction({
			readyForPlay = true;
			status = \preparedForPlay;
		});
		this.children.do({ arg child;
			child.prepareToBundle(group,bundle,true);
		});
		this.loadDefFileToBundle(bundle,server);
		this.makeResourcesToBundle(bundle);
		this.makePatchOut(group,private,bus,bundle);
	}
	makeResourcesToBundle { }
	freeResourcesToBundle { }
	
	isPlaying { ^synth.isPlaying ? false }
	cmdPeriod {
		var b;
		CmdPeriod.remove(this);
		b = CXBundle.new;
		this.stopToBundle(b);
		b.doFunctions;
		// sending the OSC is irrelevant
	}
	
	// these always call children
	stop { arg atTime;
		var b;
		if(server.notNil,{		
			b = CXBundle.new;
			this.stopToBundle(b);
			b.sendAtTime(server,atTime);
		});
		CmdPeriod.remove(this);
	}
	stopToBundle { arg bundle;
		this.children.do({ arg child;
			child.stopToBundle(bundle);
		});
		this.freeSynthToBundle(bundle);
		bundle.addMessage(this,\didStop);
		// top level only
		if(serverDeathWatcher.notNil,{
			bundle.addFunction({ serverDeathWatcher.remove });
		});
		this.freePatchOut(bundle);
	}
	didStop {		
		status = \isStopped;
		NotificationCenter.notify(this,\didStop);
	}

	free { arg atTime;
		var bundle;
		bundle = CXBundle.new;
		this.freeToBundle(bundle);
		bundle.sendAtTime(server,atTime);
	}
	freeToBundle { arg bundle;
		if(status != \freed,{
			if(status == \isPlaying,{
				// sends to all the children
				this.stopToBundle(bundle);
			});
			bundle.addMessage(this,\didFree);
			//bundle.addFunction({ readyForPlay = false; });
			this.freeResourcesToBundle(bundle);
			this.children.do({ arg child;
				child.freeToBundle(bundle);
			});
		})
	}
	didFree {
		readyForPlay = false;
		status = \freed;
	}	
	// these don't call children
	freeSynthToBundle { arg bundle;
		if(synth.isPlaying,{ // ? false
			synth.freeToBundle(bundle);
		});
		bundle.addFunction({
			synth = nil;
		});
	}
	freePatchOut { arg bundle;
		bundle.addFunction({
			//"freeing patch out".debug(this);
			patchOut.free; // free the bus
			patchOut = nil;
			group  = nil; 
			//server = nil;
			readyForPlay = false;
		});
	}
	
	run { arg flag=true;
		if(synth.notNil,{
			synth.run(flag);
		});
		//should call children ?
	}
	release { arg releaseTime,atTime;
		var rb;
		rb = CXBundle.new;
		this.releaseToBundle(releaseTime,rb);
		rb.sendAtTime(server,atTime);
	}
	releaseToBundle { arg releaseTime,bundle;
		if(synth.notNil,{
			bundle.add(synth.releaseMsg(releaseTime));
		});
		if(releaseTime ? 0.0 > 0.01,{
			bundle.addFunction({
				SystemClock.sched(releaseTime,{ 
					this.stop; 
					nil; 
				})
			});
		},{
			this.stopToBundle(bundle);
		});
	}
		
	busIndex { ^patchOut.index }
	bus { ^patchOut.bus }
	bus_ { arg b;
		if(b.notNil,{
			b = b.asBus(this.rate,this.numChannels,this.server);
			if(patchOut.notNil,{
				if(patchOut.bus != b,{
					patchOut.bus.free; 
				});
				patchOut.bus = b;
			});
			// otherwise we should have had a patchOut
			// and there is nowhere to store this
			
			if(b.numChannels != this.numChannels,{
				warn("numChannels mismatch ! " + this 
					+ this.numChannels + "vs" + b);
			});
		});
	}
	group_ { arg g;
		if(g.notNil,{
			group = g.asGroup;
		})
	}
	
	annotate { arg node,note;
		Library.put(AbstractPlayer, node.server, node.nodeID, this.asString ++ ":" ++ note);
	}
	*getAnnotation { arg node;
		^Library.at(AbstractPlayer, node.server, node.nodeID) ? "";
	}
	
	spawn { arg atTime,timeOfRequest;
		var bundle;
		bundle = CXBundle.new;
		this.spawnToBundle(bundle);
		bundle.sendAtTime(this.server,atTime,timeOfRequest);
	}
	spawnToBundle { arg bundle;
		this.children.do({ arg child;
			child.spawnToBundle(bundle);
		});
		synth = Synth.basicNew(this.defName,server);
		this.annotate(synth,"synth");
		NodeWatcher.register(synth);
		bundle.add(
			synth.addToTailMsg(this.group,this.synthDefArgs)
		);
		bundle.addMessage(this,\didSpawn);
	}
	
	spawnOn { arg group,bus, atTime,timeOfRequest;
		var bundle;
		bundle = CXBundle.new;
		this.spawnOnToBundle(group,bus,bundle);
		bundle.sendAtTime(this.server,atTime,timeOfRequest);
	}
	spawnOnToBundle { arg agroup,bus,bundle;
		if(patchOut.isNil,{
			this.makePatchOut(agroup,true,bus,bundle);
		},{
			this.bus = bus;
			this.group = agroup;
		});
		this.spawnToBundle(bundle);
	}
	didSpawn {
		status = \isPlaying;
	}

	// notifications only needed for top level play
	// or for play inside a socket
	stateNotificationsToBundle { arg b;
		b.addFunction({
			playIsStarting = false;
			status = \isSpawning;
			NotificationCenter.notify(this,\didPlay);
		});
	}		


	/*
		if defName != classname
			when player saves, save defName and secret args (name -> inputIndex)
				that means you can quickly check, load and execute synthdef
					
		save it all in InstrSynthDef (patch is only one with secret args so far)
	*/
	makePatchOut { arg agroup,private = false,bus,bundle;
		group = agroup.asGroup;
		server = group.server;
		this.topMakePatchOut(group,private,bus);
		this.childrenMakePatchOut(group,true,bundle);
	}
	topMakePatchOut { arg agroup,private = false,bus;
		this.group = agroup;
		if(patchOut.notNil,{
			if(bus.notNil,{
				this.bus = bus;
				^patchOut
			});
			if(this.rate == \audio,{
				//check if private status changed
				if(private,{
					if(patchOut.bus.notNil,{
						if(patchOut.bus.isAudioOut,{
							patchOut.bus.free;
						},{
							^patchOut
						});
					});
					patchOut.bus = Bus.audio(group.server,this.numChannels);
				},{
					if(patchOut.bus.notNil,{
						if(patchOut.bus.isAudioOut,{
							patchOut.bus.free;
						},{
							^patchOut
						})
					});
					patchOut.bus = Bus(\audio,0,this.numChannels,group.server);
				})
			});
			^patchOut
		},{
			//Patch doesn't know its numChannels or rate until after it makes the synthDef
			if(this.rate == \audio,{// out yr speakers
				if(private,{
					this.setPatchOut(
						AudioPatchOut(this,group,bus 
								?? {Bus.audio(group.server,this.numChannels)})
						)
				},{
					this.setPatchOut(
						AudioPatchOut(this,group,bus 
								?? {Bus(\audio,0,this.numChannels,group.server)})
								)
				})
			},{
				if(this.rate == \control,{
					this.setPatchOut(
						ControlPatchOut(this,group,
								bus ?? {Bus.control(group.server,this.numChannels)})
							)
				},{
					if(this.rate.isNil,{
						die("Nil rate :",this);
					});
					this.setPatchOut(
						ScalarPatchOut(this,group,bus)
					)
				});
			});
		});
				
		^patchOut
	}
	childrenMakePatchOut { arg group,private = true,bundle;
		this.children.do({ arg child;
			child.makePatchOut(group,private,nil,bundle)
		});
	}
	setPatchOut { arg po; // not while playing
		patchOut = po;
		if(patchOut.notNil,{
			server = patchOut.server;
		});
	}
	
	loadDefFileToBundle { arg bundle,server;
		var def,bytes,dn;

		// can't assume the children are unchanged
		this.children.do({ arg child;
			child.loadDefFileToBundle(bundle,server);
		});

		dn = this.defName;
		if(dn.isNil or: {
			dn = dn.asSymbol;
			if(Library.at(SynthDef,server,dn).isNil,{
				true
			},{
				false
			})
		},{
			// save it in the archive of the player or at least the name.
			// Patches cannot know their defName until they have built
			//( "building:" + (this.path ? this) ).debug;
			def = this.asSynthDef;
			defName = def.name;
			dn = defName.asSymbol;
			bytes = def.asBytes;
			bundle.add(["/d_recv", bytes]);
			// even if name was nil before (Patch), its set now
			//("loading def:" + defName).debug;
			// InstrSynthDef watches \serverRunning to clear this
			InstrSynthDef.watchServer(server);
			Library.put(SynthDef,server,dn,true);
			// write for next time
			//def.writeDefFile;
		});
	}
	//for now:  always sending, not writing
	writeDefFile {  arg dir;
		this.asSynthDef.writeDefFile(dir);
		this.children.do({ arg child;
			child.writeDefFile(dir);
		});
	}
	addToSynthDef {  arg synthDef,name;
		// value doesn't matter so much, we are going to pass in a real live one
		//this.synthArg
		synthDef.addIr(name, 0); // \out is an .ir bus index
	}

	synthArg { ^patchOut.synthArg }
	instrArgFromControl { arg control;
		// a Patch could be either
		^if(this.rate == \audio,{
			In.ar(control,this.numChannels)
		},{
			In.kr(control,this.numChannels)
		})
	}
	
	/** SUBCLASSES SHOULD IMPLEMENT **/
	//  this works for simple audio function subclasses
	//  but its probably more complicated if you have inputs
	asSynthDef { 
		^SynthDef(this.defName,{ arg out = 0;
			if(this.rate == \audio,{
				Out.ar(out,this.ar)
			},{
				Out.kr(out,this.kr)
			})
		})
	}
	synthDefArgs { 
		^[\out,patchOut.synthArg]		
	}
	defName {
		^defName ?? {this.class.name.asString}
	}
	rate { ^\audio }
	numChannels { ^1 }
	spec { 
		^if(this.rate == \audio,{
			AudioSpec(this.numChannels)
		},{	
			ControlSpec(-1,1)
			// or trig
		})
	}

	/** hot patching **/
	connectTo { arg hasInput;
		this.connectToPatchIn(hasInput.patchIn,this.isPlaying ? false);
	}
	connectToInputAt { arg player,inputIndex=0;
		this.connectToPatchIn(player.patchIns.at(inputIndex),this.isPlaying ? false)
	}
	connectToPatchIn { arg patchIn,needsValueSetNow = true;
		// if my bus is public, change to private
		if(this.isPlaying and: {this.rate == \audio} and: {this.bus.isAudioOut},{
			this.bus = Bus.alloc(this.rate,this.server,this.numChannels);
		});
		if(patchOut.isNil,{
			"no PatchOut: this object not prepared".error;
			this.dump;
		});
		this.patchOut.connectTo(patchIn,needsValueSetNow)
	}
	disconnect {
		patchOut.disconnect;
	}


	/* UGEN STYLE USAGE */
	ar {
		^this.subclassResponsibility(thisMethod)
	}
	kr { ^this.ar }
	value {  ^this.ar }
	valueArray { ^this.value }
//	inAr {
//		// only works immediately in  { }.play
//		// for quick experimentation, does not encourage reuse
//		// ideally would add itself as a child to the current InstrSynthDef
//		this.play;
//		^In.ar(this.busIndex,this.numChannels)
//	}
	// ugen style syntax
	*ar { arg ... args;
		^this.performList(\new,args).ar
	}
	*kr { arg ... args;
		^this.performList(\new,args).kr
	}

	// function composition
	composeUnaryOp { arg operator;
		^PlayerUnop.new(operator, this)
	}
	composeBinaryOp { arg operator, pattern;
		^PlayerBinop.new(operator, this, pattern)
	}
	reverseComposeBinaryOp { arg operator, pattern;
		^PlayerBinop.new(operator, pattern, this)
	}

	// subclasses need only implement beatDuration 
	beatDuration { ^nil } // nil means inf
	timeDuration { var bd;
		bd = this.beatDuration;
		if(bd.notNil,{
			^Tempo.beats2secs(bd)
		},{
			^nil
		});	
	}
	delta { 	^this.beatDuration	}

	// support Pseq([ aPlayer, aPlayer2],inf) etc.
	// you need to have prepared me and set any busses.
	// i need to have a finite duration.
	embedInStream { arg inval;
		^PlayerEvent(this)
	}
	
	// if i am saved/loaded from disk my name is my filename
	// otherwise it is "a MyClassName"
	name { 
		^(name ?? 
		{
			name = if(path.notNil,{ 
						PathName(path).fileName
					},nil);
			name  
		}) 
	}
	asString { ^this.name ?? { super.asString } }

	path_ { arg p; 
		path = if(p.isNil,p ,{
			path = PathName(p).asRelativePath
		}) 
	}

	save { arg apath;
		var evpath;
		if(File.exists(apath),{
			evpath = apath.escapeChar($ );
			("cp " ++ evpath + evpath ++ ".bak").unixCmd;
		});
		this.asCompileString.write(apath);
		if(path != apath,{ this.didSaveAs(apath); });
	}
	didSaveAs { arg apath;
		path = apath;
		NotificationCenter.notify(AbstractPlayer,\saveAs,[this,path]);
		/* to receive this:
			NotificationCenter.register(AbstractPlayer,\saveAs,you,
			{ arg model,path;
				// do any saveAs handlers you wish
			});
		*/
	}
	
	
	// structural utilities
	children { ^#[] }
	deepDo { arg function;// includes self
		function.value(this);
		this.children.do({arg c; 
			var n;
			n = c.tryPerform(\deepDo,function);
			if(n.isNil,{ function.value(c) });
		});
	}	 
	allChildren { 
		var all;
		all = Array.new;
		this.deepDo({ arg child; all = all.add(child) });
		^all
		// includes self
	}
	
	asCompileString { // support arg sensitive formatting
		var stream;
		stream = PrettyPrintStream.on(String(256));
		this.storeOn(stream);
		^stream.contents
	}
	storeParamsOn { arg stream;
		// anything with a path gets stored as abreviated
		var args;
		args = this.storeArgs;
		if(args.notEmpty,{
			if(stream.isKindOf(PrettyPrintStream),{
				stream.storeArgs( enpath(args) );
			},{
				stream << "(" <<<* enpath(args) << ")"
			});
		})
	}
	// using the arg passing version
	changed { arg what ... moreArgs;
		dependantsDictionary.at(this).do({ arg item;
			item.performList(\update, this, what, moreArgs);
		});
	}

	guiClass { ^AbstractPlayerGui }

}


SynthlessPlayer : AbstractPlayer { // should be higher

	var <isPlaying=false;

	loadDefFileToBundle { }

	spawnToBundle { arg bundle;
		this.children.do({ arg child;
			child.spawnToBundle(bundle);
		});
		bundle.addMessage(this,\didSpawn);
	}
	didSpawn {
		super.didSpawn;
		isPlaying = true;
	}
	didStop {		
		super.didStop;
		isPlaying = false;
	}
	releaseToBundle { arg releaseTime = 0.1,bundle;
		// children release  ?
		bundle.addMessage(this,\stop);
	}
	connectToPatchIn { arg patchIn,needsValueSetNow = true;
		this.patchOut.connectTo(patchIn,needsValueSetNow)
	}
}


MultiplePlayers : AbstractPlayer { // abstract

	var <>voices;

	children { ^this.voices }

	rate { ^this.voices.first.rate }
	numChannels { ^this.voices.first.numChannels }

//	releaseToBundle { arg releaseTime = 0.1,bundle;
//		this.voices.do({ arg pl;
//			pl.releaseToBundle(releaseTime,bundle)
//		});
//		super.releaseToBundle(releaseTime,bundle);
//	}
}

MultiTrackPlayer : MultiplePlayers { // abstract
	
}

AbstractPlayerProxy : AbstractPlayer { // won't play if source is nil

	var <>source,<isPlaying = false, <isSleeping = true,sharedBus;

	asSynthDef { ^this.source.asSynthDef }
	synthDefArgs { ^this.source.synthDefArgs }
	synthArg { ^this.source.synthArg }
	rate { ^this.source.rate }
	numChannels { ^this.source.numChannels }
	loadDefFileToBundle { arg b,server;
		if(this.source.notNil,{
			this.source.loadDefFileToBundle(b,server)
		})
	}
	defName { ^this.source.defName }
	spawnToBundle { arg bundle; 
		this.source.spawnToBundle(bundle);
		bundle.addMessage(this,\didSpawn);
	}
	didSpawn {
		super.didSpawn;
		isPlaying = true;
		if(this.source.notNil, { isSleeping = false });
	}
	instrArgFromControl { arg control;
		^this.source.instrArgFromControl(control)
	}
	initForSynthDef { arg synthDef,argi;
		// only used for building the synthDef
		this.source.initForSynthDef(synthDef,argi)
	}
	connectToPatchIn { arg patchIn, needsValueSetNow=true;
		this.source.connectToPatchIn(patchIn,needsValueSetNow);
	}
	didStop {
		isPlaying = false;
		isSleeping = true;
		status = \isStopped;
	}

	children { 
		if(this.source.notNil,{
			^[this.source] 
		},{
			^[]
		});
	}
	
	makePatchOut { arg agroup,private,bus,bundle;
		super.topMakePatchOut(agroup,private,bus,bundle);
		if(patchOut.bus.notNil,{ // could be a scalar out
			sharedBus = SharedBus.newFrom(patchOut.bus,this);
			patchOut.bus = sharedBus;
		});
		this.children.do({ arg child;
			child.makePatchOut(group,private,sharedBus,bundle);
		});
	}
	freePatchOut { arg bundle;
		super.freePatchOut(bundle);
		bundle.addFunction({
			if(sharedBus.notNil,{
				sharedBus.releaseBus(this);
			});
			sharedBus = nil;
		});
	}
}

