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

		// build a mono (mxPlayer1) and a stereo (mxPlayer2) variant; numCh is fixed
		// at compile time so each produces the correct graph for its buffer type
		[1, 2].do({ arg numCh;
			SynthDef("mxPlayer" ++ numCh,{
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
					snd = LPF.ar(snd,lpf);
					snd = HPF.ar(snd,hpf);
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
					Out.ar(outDelay,snd*delaySend);
					Out.ar(outReverb,snd*reverbSend);
					Out.ar(0,snd)
			}).add;
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
		this.addCommand("mxsamplesload","is", { arg msg;
			// lua is sending 0-index
			var i = msg[1];
			sampleBuffMxSamples[i].free;
			sampleBuffMxSamples[i] = Buffer.read(context.server, msg[2], action: { arg buf;
				sampleBuffChannels[i] = buf.numChannels;
			});
		});

		this.addCommand("mxsampleson","iiffffffffffff", { arg msg;
			var name=msg[1];
			var numCh = min(sampleBuffChannels[msg[2]], 2); // 1→mono, 2→stéréo, >2→best-effort stéréo
			if (mxsamplesVoices.at(name)!=nil,{
				if (mxsamplesVoices.at(name).isRunning==true,{
					mxsamplesVoices.at(name).free;
				});
			});
			mxsamplesVoices.put(name,
				Synth.before(mxsamplesFX,"mxPlayer" ++ numCh,[
					\t_trig,1,
					\outDelay,mxsamplesBusDelay,
					\outReverb,mxsamplesBusReverb,
					\envgate,1,
					\bufnum,sampleBuffMxSamples[msg[2]],
					\rate,msg[3],
					\amp,msg[4],
					\pan,msg[5],
					\attack,msg[6],
					\decay,msg[7],
					\sustain,msg[8],
					\release,msg[9],
					\lpf,msg[10],
					\hpf,msg[11],
					\delaySend,msg[12],
					\reverbSend,msg[13],
					\sampleStart,msg[14] ]).onFree({
					NetAddr("127.0.0.1", 10111).sendMsg("voice",name,0);
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
