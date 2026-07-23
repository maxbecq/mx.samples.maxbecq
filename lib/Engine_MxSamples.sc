// Engine_MxSamples

// Inherit methods from CroneEngine
Engine_MxSamples : CroneEngine {

	// <mxsamples>
	var sampleBuffMxSamples;
	var sampleBuffChannels;
	var sampleBuffMxSamplesDelay;
	var mxsamplesMaxVoices=40;
	var mxsamplesFX;
	var mxsamplesBusDelay;
	var mxsamplesBusReverb;
	var fnNoteOn, fnNoteOff;
	var mxsamplesVoices;
	var mxsamplesVoicesOn;
	var pedalSustainOn=false;
	var pedalSostenutoOn=false;
	var pedalSustainNotes;
	var pedalSostenutoNotes;
	// </mxsamples>

	*new { arg context, doneCallback;
		^super.new(context, doneCallback);
	}

	alloc {
		// <mxsamples>
		mxsamplesVoices=Dictionary.new;
		mxsamplesVoicesOn=Dictionary.new;
		pedalSustainNotes=Dictionary.new;
		pedalSostenutoNotes=Dictionary.new;

		context.server.sync;

		sampleBuffMxSamples = Array.fill(80, { arg i;
			Buffer.new(context.server);
		});
		sampleBuffChannels = Array.fill(80, { 2 }); // défaut stéréo tant que non chargé
		sampleBuffMxSamplesDelay = Buffer.alloc(context.server,48000,2);

		// delay uniquement : la reverb est deportee vers la reverb systeme norns
		// (Zita dans crone, autre coeur que scsynth) pour repartir la charge CPU
		SynthDef("mxfx",{
			arg inDelay, inReverb, out, secondsPerBeat=1,delayBeats=4,delayFeedback=0.1,bufnumDelay;
			var snd;

			// delay
			snd = In.ar(inDelay,2);
			snd = CombC.ar(
				snd,
				2,
				secondsPerBeat*delayBeats,
				secondsPerBeat*delayBeats*LinLin.kr(delayFeedback,0,1,2,128),// delayFeedback should vary between 2 and 128
			);
			Out.ar(out,snd);
		}).add;

		// build mono (mxPlayer1) and stereo (mxPlayer2) variants; numCh is fixed
		// at compile time so each produces the correct graph for its buffer type.
		// chaque variante existe aussi en "lite" (sans LPF/HPF ni sorties sends) :
		// choisie au note-on quand filtres au neutre et sends a 0, pour reduire
		// le cout CPU par voix (cas nominal du script)
		[1, 2].do({ arg numCh;
			[true, false].do({ arg full;
				SynthDef("mxPlayer" ++ numCh ++ (full.if({""},{"lite"})),{
						arg outDelay,outReverb,bufnum, amp=0.0, t_trig=0,envgate=1,name=1,
						attack=0.015,decay=1,release=2,sustain=0.9,
						sampleStart=0,sampleEnd=1,rate=1,pan=0,
						lpf=20000,hpf=10,delaySend=0,reverbSend=0;

						// vars
						var ender,snd;

						ender = EnvGen.ar(
							Env.new(
								curve: 'cubed',
								levels: [0,1,sustain,0],
								times: [attack+0.015,decay,release],
								releaseNode: 2,
							),
							gate: envgate,
						);

						snd = PlayBuf.ar(numCh, bufnum,
							rate:BufRateScale.kr(bufnum)*rate,
							startPos: ((sampleEnd*(rate<0))*BufFrames.kr(bufnum))+(sampleStart/1000*48000),
							trigger:t_trig,
						);
						if (full, {
							snd = LPF.ar(snd,lpf);
							snd = HPF.ar(snd,hpf);
						});
						if (numCh == 1, {
							// mono: même signal dans les deux Pan2 → centré à pan=0, sans décorrélation
							snd = Mix.ar([
								Pan2.ar(snd,-1+(2*pan),amp),
								Pan2.ar(snd,1+(2*pan),amp),
							]);
						}, {
							snd = Mix.ar([
								Pan2.ar(snd[0],-1+(2*pan),amp),
								Pan2.ar(snd[1],1+(2*pan),amp),
							]);
						});
						snd = snd * amp * ender;

						// SendTrig.kr(Impulse.kr(1),name,1);
						DetectSilence.ar(snd,doneAction:2);
						// just in case, release after 1 minute
						FreeSelf.kr(TDelay.kr(DC.kr(1),60));
						if (full, {
							Out.ar(outDelay,snd*delaySend);
							Out.ar(outReverb,snd*reverbSend);
						});
						Out.ar(0,snd)
				}).add;
			});
		});

		// initialize fx synth and bus
		context.server.sync;
		mxsamplesBusDelay = Bus.audio(context.server,2);
		mxsamplesBusReverb = Bus.audio(context.server,2);
		context.server.sync;
		mxsamplesFX = Synth.new("mxfx",[\out,0,\inDelay,mxsamplesBusDelay,\inReverb,mxsamplesBusReverb]);
		mxsamplesFX.run(false); // demarre en pause : zero cout CPU tant que reverb/delay inutilises
		context.server.sync;


		// initialize 
		// intialize helper functions		
		fnNoteOff = {
			arg name;
			mxsamplesVoicesOn.removeAt(name);
			if (pedalSustainOn==true,{
				pedalSustainNotes.put(name,1);
			},{
				if ((pedalSostenutoOn==true)&&(pedalSostenutoNotes.at(name)!=nil),{
					// do nothing, it is a sostenuto note
				},{
					// remove the sound
					mxsamplesVoices.at(name).set(\envgate,0);
				});
			});
		};


		this.addCommand("mxsamplesrelease","", { arg msg;
			(0..79).do({arg i; sampleBuffMxSamples[i].free});
		});
		this.addCommand("mxsamplesload","iis", { arg msg;
			// lua is sending 0-index; msg[2] est un id de chargement que lua
			// utilise pour apparier la confirmation (slot reutilise entre-temps)
			var i = msg[1];
			var id = msg[2];
			sampleBuffMxSamples[i].free;
			sampleBuffMxSamples[i] = Buffer.read(context.server, msg[3], action: { arg buf;
				sampleBuffChannels[i] = buf.numChannels;
				// confirme a lua que la lecture disque est terminee :
				// avant ca, jouer ce slot sortirait le contenu perime
				NetAddr("127.0.0.1", 10111).sendMsg("mxsamples_loaded", i, id);
			});
		});
		this.addCommand("mxsamplesunload","i", { arg msg;
			// evince un slot (plafond ram cote lua) : libere le buffer serveur
			var i = msg[1];
			sampleBuffMxSamples[i].free;
			sampleBuffMxSamples[i] = Buffer.new(context.server);
			sampleBuffChannels[i] = 2;
		});

		this.addCommand("mxsampleson","iiiffffffffffff", { arg msg;
			var name=msg[1];
			// gen : id de note renvoye dans l'osc "voice" ; lua ignore ainsi les
			// liberations perimees (voix deja reutilisee par une nouvelle note)
			var gen=msg[2];
			var old=mxsamplesVoices.at(name);
			var numCh = min(sampleBuffChannels[msg[3]], 2); // 1→mono, 2→stéréo, >2→best-effort stéréo
			// variante lite si filtres au neutre (lpf>=19k, hpf<=30) et sends a 0 :
			// choix definitif au note-on (les voix sont set-and-forget, seul envgate change)
			var lite = (msg[11] >= 19000) and: { msg[12] <= 30 } and: { msg[13] <= 0 } and: { msg[14] <= 0 };
			if (old!=nil,{
				if (old.isRunning==true,{
					// vol de voix en fondu de 50 ms au lieu d'un free sec (clic
					// en pleine forme d'onde) ; DetectSilence libere ensuite
					old.set(\release,0.05,\envgate,0);
				});
			});
			mxsamplesVoices.put(name,
				Synth.before(mxsamplesFX,"mxPlayer" ++ numCh ++ (lite.if({"lite"},{""})),[
					\t_trig,1,
					\outDelay,mxsamplesBusDelay,
					\outReverb,mxsamplesBusReverb,
					\envgate,1,
					\bufnum,sampleBuffMxSamples[msg[3]],
					\rate,msg[4],
					\amp,msg[5],
					\pan,msg[6],
					\attack,msg[7],
					\decay,msg[8],
					\sustain,msg[9],
					\release,msg[10],
					\lpf,msg[11],
					\hpf,msg[12],
					\delaySend,msg[13],
					\reverbSend,msg[14],
					\sampleStart,msg[15] ]).onFree({
					NetAddr("127.0.0.1", 10111).sendMsg("voice",name,0,gen);
				});
			);
			mxsamplesVoicesOn.put(name,1);
			NodeWatcher.register(mxsamplesVoices.at(name));
		});

		// pause/reprend le synth FX (reverb+delay) : .run(false) supprime son cout CPU
		// sans le detruire (reversible). Utile si reverb/delay inutilises.
		this.addCommand("mxsamplesfx","i", { arg msg;
			mxsamplesFX.run(msg[1]==1);
		});

		this.addCommand("mxsamplesoff","i", { arg msg;
			// lua is sending 1-index
			var name=msg[1];
			if (mxsamplesVoices.at(name)!=nil,{
				if (mxsamplesVoices.at(name).isRunning==true,{
					fnNoteOff.(name);
				});
			});
		});

		this.addCommand("mxsamples_delay_time","f", { arg msg;
			mxsamplesFX.set(\secondsPerBeat,msg[1])
		});

		this.addCommand("mxsamples_delay_beats","f", { arg msg;
			mxsamplesFX.set(\delayBeats,msg[1])
		});

		this.addCommand("mxsamples_delay_feedback","f", { arg msg;
			mxsamplesFX.set(\delayFeedback,msg[1])
		});

		this.addCommand("mxsamples_sustain", "i", { arg msg;
			pedalSustainOn=(msg[1]==1);
			if (pedalSustainOn==false,{
				// release all sustained notes
				pedalSustainNotes.keysValuesDo({ arg note, val; 
					if (mxsamplesVoicesOn.at(note)==nil,{
						pedalSustainNotes.removeAt(note);
						fnNoteOff.(note);
					});
				});
			},{
				// add currently down notes to the pedal
				mxsamplesVoicesOn.keysValuesDo({ arg note, val; 
					pedalSustainNotes.put(note,1);
				});
			});
		});

		this.addCommand("mxsamples_sustenuto", "i", { arg msg;
			pedalSostenutoOn=(msg[1]==1);
			if (pedalSostenutoOn==false,{
				// release all sustained notes
				pedalSostenutoNotes.keysValuesDo({ arg note, val; 
					if (mxsamplesVoicesOn.at(note)==nil,{
						pedalSostenutoNotes.removeAt(note);
						fnNoteOff.(note);
					});
				});
			},{
				// add currently held notes
				mxsamplesVoicesOn.keysValuesDo({ arg note, val;
					pedalSostenutoNotes.put(note,1);
				});
			});
		});



	}

	free {
		(0..79).do({arg i; sampleBuffMxSamples[i].free});
		mxsamplesVoices.keysValuesDo({ arg key, value; value.free; });
		mxsamplesBusDelay.free;
		mxsamplesBusReverb.free;
		mxsamplesFX.free;
		sampleBuffMxSamplesDelay.free;
	}
}
