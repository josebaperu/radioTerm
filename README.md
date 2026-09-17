# tuner

Internet radio in a terminal, with a real ten band equalizer.

```
./tuner
```

ffmpeg decodes the stream; everything after that is plain Java — the filter
bank, the spectrum analyser and the interface. No third party libraries.

## Requirements

- Java 21+
- ffmpeg on `PATH` (or set `TUNER_FFMPEG` to its location)
- a terminal that speaks 256 colours and UTF-8, at least 62x18

## Keys

| Stations | |
|---|---|
| `↑` `↓` `j` `k` | move selection |
| `Enter` | tune in |
| `space` | stop / resume |
| `n` `p` | next / previous station, playing immediately |
| `PgUp` `PgDn` `g` `G` | page, top, bottom |
| `/` | filter the list, `esc` clears it |
| `f` | favourite, `s` sorts favourites to the top |
| `a` `d` | add a station, delete one |

| Equalizer (`tab` switches panels) | |
|---|---|
| `←` `→` or `0`-`9` | select a band |
| `↑` `↓` | band gain, ±1 dB up to ±12 |
| `[` `]` | cycle presets |
| `e` | bypass the filter bank |
| `r` | back to flat |
| `<` `>` | preamp |

| Anywhere | |
|---|---|
| `+` `-` | volume |
| `m` | mute |
| `?` | key help |
| `q` | quit |

## Command line

```
tuner --list                 print the stations
tuner --play "FIP Jazz"      tune by name, list number, or stream url
tuner --no-autoplay          do not resume the last station
```

## Files

The station list lives in the source tree as `stations.tsv` — tab separated,
`name`, `url`, `genre`, `favourite`, with `#` comments. Forty-one stations ship
in it (SomaFM, Radio Paradise, FIP, KEXP, NTS, WFMU, Radio Swiss, Classic FM
and others). Edit it to change the defaults everyone gets.

At startup the list is resolved in this order:

| | |
|---|---|
| `$TUNER_STATIONS` | if set, this file is used and written to, nothing else is consulted |
| `~/.config/tuner/stations.tsv` | your own copy, created the first time you add, delete, favourite or sort from inside the app |
| `stations.tsv` in the source tree | the shipped default, found next to the working directory or above the jar |
| `stations.tsv` inside the jar | same file, bundled at build time, so a jar copied elsewhere still has stations |

Edits made in the app always go to the user copy, so the source tree file stays
pristine. Delete `~/.config/tuner/stations.tsv` to go back to the shipped list.
Press `?` in the app to see which file is in use.

Volume, equalizer curve and last station are kept separately in
`~/.config/tuner/config.properties`. Both paths honour `XDG_CONFIG_HOME`.

Any stream url ffmpeg can open works — MP3, AAC, Icecast, Shoutcast or HLS.

## How it works

```
ffmpeg ──PCM s16le──▶ float ──▶ 10x biquad peaking filters (per channel)
                                   │
                                   ├──▶ preamp, volume, soft limiter ──▶ sound card
                                   └──▶ 2048 point FFT ──▶ spectrum bars
```

Each equalizer band is an RBJ peaking biquad at roughly one octave of
bandwidth, recalculated when a slider moves, so changes are audible
immediately without re-opening the stream. A soft knee limiter after the gain
stage keeps heavy boosts from clipping.

Track titles come from two places: ffmpeg reports ICY metadata changes, and a
side connection asks the server for the current `StreamTitle` so the first
track of a session shows up too. Dropped streams reconnect with a backoff.
