`cache-recovery.m4a` is a generated four-second, 440 Hz AAC test tone in an MP4 container. It contains no third-party music.

Generate it with:

```sh
ffmpeg -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=4' -c:a aac -b:a 32k -movflags +faststart -map_metadata -1 cache-recovery.m4a
```

The regression test interrupts its transfer, resumes only missing bytes, disables the network, and uses Media3's MP4 extractor to read through its final AAC sample.
