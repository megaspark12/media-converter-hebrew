Generated synthetic blue video and 440 Hz audio, not third-party media.
Two 2-second H.264/AAC MPEG-TS fragments from a six-second HLS video.
The test constructs the playlist and deliberately omits the middle fragment.

Regenerate with FFmpeg (libx264 enabled):
ffmpeg -y -f lavfi -i color=c=blue:s=64x48:r=25 -f lavfi -i sine=frequency=440:sample_rate=44100 -t 6 -c:v libx264 -preset ultrafast -g 50 -sc_threshold 0 -c:a aac -b:a 32k -f hls -hls_time 2 -hls_list_size 0 playlist.m3u8
Keep playlist0.ts and playlist2.ts only.
