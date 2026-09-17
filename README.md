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

- `~/.config/tuner/stations.tsv` — name, url, genre, favourite; editable by hand
- `~/.config/tuner/config.properties` — volume, equalizer curve, last station

Forty-one stations ship by default (SomaFM, Radio Paradise, FIP, KEXP, NTS,
WFMU, Radio Swiss, Classic FM and others). Add your own with `a`, or paste any
stream url ffmpeg can open — MP3, AAC, Icecast, Shoutcast or HLS.

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
