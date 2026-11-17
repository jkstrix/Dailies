# Third-party notices

Dailies itself is under the MIT licence (see LICENSE). Its jar also carries the software below, unchanged.

## FFmpeg

- **What:** the FFmpeg libraries and the `ffmpeg` program, used to encode exported video.
- **Version:** FFmpeg 8.1.2, as packaged by the JavaCPP Presets, artifact `org.bytedeco:ffmpeg:8.1.2-1.5.14`, platform `windows-x86_64`. This is the build without the `-gpl` classifier.
- **Licence:** GNU Lesser General Public License, version 3 or later (https://www.gnu.org/licenses/lgpl-3.0.html). Libraries built into it carry their own licences, which travel inside the packed jar.
- **Where it is:** `encoder/ffmpeg-windows-x86_64.jar` inside the Dailies jar. On first export its files are unpacked to `<game>/dailies/encoder/windows-x86_64/`.
- **Replacing it:** Dailies runs the encoder as a separate program and does not link against it. Any FFmpeg build can be used in its place by replacing the unpacked files in that folder.
- **Source:** https://ffmpeg.org/ and https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg

Which codecs Dailies offers is read from this build when it runs; nothing is offered that the build does not contain.
