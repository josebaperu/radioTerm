package org.example.radio.model;

import java.util.List;

/** Shipped station list, all verified public streams. */
public final class DefaultStations {

    public static final List<Station> LIST = List.of(
            new Station("SomaFM Groove Salad", "https://ice1.somafm.com/groovesalad-128-mp3", "ambient / chill", true),
            new Station("SomaFM Drone Zone", "https://ice1.somafm.com/dronezone-128-mp3", "ambient"),
            new Station("SomaFM Deep Space One", "https://ice1.somafm.com/deepspaceone-128-mp3", "space ambient"),
            new Station("SomaFM Lush", "https://ice1.somafm.com/lush-128-mp3", "vocal chill"),
            new Station("SomaFM Secret Agent", "https://ice1.somafm.com/secretagent-128-mp3", "spy lounge"),
            new Station("SomaFM Beat Blender", "https://ice1.somafm.com/beatblender-128-mp3", "deep house"),
            new Station("SomaFM Space Station", "https://ice1.somafm.com/spacestation-128-mp3", "electronica"),
            new Station("SomaFM Indie Pop Rocks", "https://ice1.somafm.com/indiepop-128-mp3", "indie pop"),
            new Station("SomaFM Underground 80s", "https://ice1.somafm.com/u80s-128-mp3", "synth / new wave"),
            new Station("SomaFM DEF CON Radio", "https://ice1.somafm.com/defcon-128-mp3", "hacker electronica"),
            new Station("SomaFM Metal Detector", "https://ice1.somafm.com/metal-128-mp3", "metal"),
            new Station("SomaFM Sonic Universe", "https://ice1.somafm.com/sonicuniverse-128-mp3", "jazz fusion"),
            new Station("SomaFM Boot Liquor", "https://ice1.somafm.com/bootliquor-128-mp3", "americana"),
            new Station("SomaFM Left Coast 70s", "https://ice1.somafm.com/seventies-128-mp3", "70s rock"),
            new Station("SomaFM PopTron", "https://ice1.somafm.com/poptron-128-mp3", "electro pop"),
            new Station("SomaFM The Trip", "https://ice1.somafm.com/thetrip-128-mp3", "progressive"),
            new Station("SomaFM Fluid", "https://ice1.somafm.com/fluid-128-mp3", "instrumental hip hop"),
            new Station("SomaFM Covers", "https://ice1.somafm.com/covers-128-mp3", "cover versions"),
            new Station("SomaFM Mission Control", "https://ice2.somafm.com/missioncontrol-128-mp3", "nasa ambient"),
            new Station("Radio Paradise Main", "https://stream.radioparadise.com/mp3-192", "eclectic rock", true),
            new Station("Radio Paradise Mellow", "https://stream.radioparadise.com/mellow-192", "mellow mix"),
            new Station("Radio Paradise Rock", "https://stream.radioparadise.com/rock-192", "rock mix"),
            new Station("Radio Paradise Global", "https://stream.radioparadise.com/global-192", "world / groove"),
            new Station("FIP", "https://icecast.radiofrance.fr/fip-midfi.mp3", "eclectic", true),
            new Station("FIP Jazz", "https://icecast.radiofrance.fr/fipjazz-midfi.mp3", "jazz"),
            new Station("FIP Groove", "https://icecast.radiofrance.fr/fipgroove-midfi.mp3", "groove / soul"),
            new Station("FIP Rock", "https://icecast.radiofrance.fr/fiprock-midfi.mp3", "rock"),
            new Station("FIP Pop", "https://icecast.radiofrance.fr/fippop-midfi.mp3", "pop"),
            new Station("FIP Electro", "https://icecast.radiofrance.fr/fipelectro-midfi.mp3", "electro"),
            new Station("France Inter", "https://icecast.radiofrance.fr/franceinter-midfi.mp3", "talk / culture"),
            new Station("KEXP Seattle", "https://kexp-mp3-128.streamguys1.com/kexp128.mp3", "alternative"),
            new Station("WFMU Freeform", "https://stream0.wfmu.org/freeform-128k", "freeform"),
            new Station("NTS Radio 1", "https://stream-relay-geo.ntslive.net/stream", "underground"),
            new Station("NTS Radio 2", "https://stream-relay-geo.ntslive.net/stream2", "underground"),
            new Station("Radio Swiss Jazz", "https://stream.srg-ssr.ch/m/rsj/mp3_128", "jazz"),
            new Station("Radio Swiss Classic", "https://stream.srg-ssr.ch/m/rsc_de/mp3_128", "classical"),
            new Station("Radio Swiss Pop", "https://stream.srg-ssr.ch/m/rsp/mp3_128", "pop"),
            new Station("Classic FM", "https://media-ssl.musicradio.com/ClassicFMMP3", "classical"),
            new Station("Cadena SER", "https://21223.live.streamtheworld.com/CADENASER.mp3", "es / talk"),
            new Station("Rock FM", "https://rockfm-cope.flumotion.com/playlist.m3u8", "es / rock"),
            new Station("Lo-Fi Radio", "https://stream.laut.fm/lofi", "lofi beats")
    );

    private DefaultStations() {
    }
}
