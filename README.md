# mx.samples.maxbecq

Personal fork of [mx.samples](https://github.com/schollz/mx.samples) by infinitedigits (schollz).

Like mr.coffee or mr.radar, but for instrument samples — with crow CV/gate output and a curated instrument list.

## Changes from the original

### Crow integration
- Each MIDI note sends a **trigger** on crow output 3 (5V impulse) and **pitch** (V/oct) on crow output 4
- Allows driving an eurorack synth in parallel with the norns samples
- Standard 1V/oct, C4 (MIDI 60) = 0V

### Velocity range control
- New parameters **min velocity** and **max velocity** in `PARAMETERS -> MX.SAMPLES`
- Allows clamping incoming MIDI velocity to a fixed range
- Default velocity sensitivity set to "fixed"

### Autosave
- Parameters are automatically saved every 10 seconds
- On script load, the last saved parameter state is restored automatically

### Curated instrument list
- Reduced instrument list to a personal selection (others are commented out, easy to re-enable)
- Added new instruments: **Departure**, **Fazioli**
- Active instruments: cello pad, claus piano wpedal, Departure, dictaphone, Fazioli, kawai felt, kalimba, music box, piano soft, string spurs, wind chimes

### Other changes
- Script renamed to `mx.samples.maxbecq` (standalone, not nested under `mx.samples/`)
- Library include path changed from `mx.samples/lib/mx.samples` to `lib/mx.samples`
- Last instrument selection saved in `norns.state.data` instead of `_path.data.."mx.samples/"`

## Requirements

- norns
- MIDI controller
- crow (optional, for CV/gate output to eurorack)

## Documentation

See the [original mx.samples documentation](https://github.com/schollz/mx.samples) for general usage (keyboard mode, library mode, sample processing).

### Crow outputs

| Output | Signal | Description |
|--------|--------|-------------|
| 3 | Trigger | 5V impulse on each note_on |
| 4 | Pitch | V/oct (C4 = 0V) |

Outputs 1 and 2 are free for other scripts or modules.

## Download

`;install https://github.com/maxbecq/mx.samples.maxbecq`

## Credits

Based on [mx.samples](https://github.com/schollz/mx.samples) by infinitedigits (schollz).
