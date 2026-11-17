# Third-party notices

Dailies itself is under the MIT licence (see `LICENSE`). Its jar also carries the video encoder below, unchanged, as
a separate program. This file and the licence texts it names are inside the jar too: this file at the top, the texts
in the `licenses` folder.

## Platforms

A video encoder is packed in for **Windows on 64-bit Intel or AMD processors (windows-x86_64)** only, for now.
On Linux and macOS, and on Windows on ARM, Dailies says so in the Export tab and exports numbered PNG pictures;
everything else in the mod works the same. Encoders for the other platforms are to come from a build of our own
(see "Rebuilding" below).

## FFmpeg

- **What:** the FFmpeg libraries and the `ffmpeg` and `ffprobe` programs, used to encode exported video and sound.
- **Version:** FFmpeg 8.1.2, as packaged by the JavaCPP Presets, artifact `org.bytedeco:ffmpeg:8.1.2-1.5.14`,
  platform `windows-x86_64`. This is the build without the `-gpl` classifier: it is configured with
  `--enable-version3` and without `--enable-gpl` or `--enable-nonfree`.
- **Licence:** GNU Lesser General Public License, version 3 or later: `licenses/LGPL-3.0-or-later.txt`, which adds to
  the GNU General Public License, version 3: `licenses/GPL-3.0-or-later.txt`.
- **Where it is:** `encoder/ffmpeg-windows-x86_64.jar` inside the Dailies jar. On first export its files are unpacked
  to `<game>/dailies/encoder/windows-x86_64/`.
- **Replacing it:** Dailies runs the encoder as a separate program and does not link against it. Any FFmpeg build can
  be used in its place by replacing the unpacked files in that folder, which is what the LGPL asks to be possible.
- **Source:** https://ffmpeg.org/ and https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg (the build
  script `cppbuild.sh` there names the exact version of every library below and fetches its source).

## Libraries built into that FFmpeg

The list is taken from the build's own configuration line (`ffmpeg -version`). Each library is under the licence
named, whose text is in the `licenses` folder.

| Library | Used for | Licence | Text |
|---|---|---|---|
| SVT-AV1 (Alliance for Open Media) | AV1 video, software | BSD 3-Clause Clear, with the Alliance for Open Media Patent License 1.0 | `BSD-3-Clause-Clear.txt` |
| libaom (Alliance for Open Media) | AV1 video, software | BSD 2-Clause, with the Alliance for Open Media Patent License 1.0 | `BSD-2-Clause.txt` |
| libvpx (The WebM Project) | VP8 and VP9 video | BSD 3-Clause, with a patent grant | `BSD-3-Clause.txt` |
| libopus (Xiph.Org and others) | Opus sound | BSD 3-Clause, with patent grants | `BSD-3-Clause.txt` |
| OpenH264 (Cisco Systems) | Not used by Dailies, see below | BSD 2-Clause | `BSD-2-Clause.txt` |
| LAME (libmp3lame) | Not used by Dailies | GNU Library General Public License, version 2 or later; taken here as version 2.1 | `LGPL-2.1-or-later.txt` |
| Speex (Xiph.Org) | Not used by Dailies | BSD 3-Clause | `BSD-3-Clause.txt` |
| OpenCORE AMR, VisualOn AMR-WB | Not used by Dailies | Apache License 2.0 | `Apache-2.0.txt` |
| OpenSSL | Not used by Dailies (network protocols) | Apache License 2.0 | `Apache-2.0.txt` |
| libsrt (Haivision) | Not used by Dailies (network protocols) | Mozilla Public License 2.0 | `MPL-2.0.txt` |
| FreeType | Not used by Dailies (text drawing) | FreeType Project License | `FTL.txt` |
| HarfBuzz | Not used by Dailies (text drawing) | MIT ("Old MIT") | `MIT.txt` |
| libxml2 | Not used by Dailies | MIT | `MIT.txt` |
| libwebp (Google) | Not used by Dailies | BSD 3-Clause, with a patent grant | `BSD-3-Clause.txt` |
| zimg | Not used by Dailies | WTFPL, version 2 | `WTFPL.txt` |
| zlib | Compression inside file formats | zlib licence | `Zlib.txt` |
| Intel Media SDK dispatcher (libmfx) | Intel Quick Sync hardware encoding | MIT | `MIT.txt` |
| NVIDIA codec headers (nv-codec-headers) | NVIDIA hardware encoding | MIT | `MIT.txt` |
| mingw-w64 winpthreads (`libwinpthread-1.dll`) | Threads on Windows | MIT and BSD 3-Clause | `MIT.txt`, `BSD-3-Clause.txt` |
| GCC runtime libraries, linked in | The C and C++ runtime | GNU General Public License, version 3 or later, with the GCC Runtime Library Exception | `GPL-3.0-or-later.txt` |
| JavaCPP Presets glue (`jni*.dll`, Bytedeco) | Not used by Dailies; unpacked with the rest | Apache License 2.0 | `Apache-2.0.txt` |

The Alliance for Open Media Patent License 1.0 is at https://aomedia.org/license/patent-license/.

The hardware encoders (NVIDIA, Intel, and Windows Media Foundation) are part of the graphics driver or of Windows;
Dailies carries only the open headers needed to call them.

## H.264 and H.265

Dailies makes H.264 and H.265 only through hardware encoders. It carries no software encoder for either: x264 and
x265 would need a GPL build, and Cisco's patent licence for OpenH264 covers only binaries fetched from Cisco. The
packaged FFmpeg above happens to contain OpenH264; **Dailies never uses it**, neither by default nor when asked for it
by name. A computer without a hardware H.264 encoder is offered AV1, VP9, or an editing master (ProRes or DNxHR).

## What Dailies offers

Which codecs are offered is read from this build when it runs, and every hardware encoder is tried once on the
machine before it is listed. Nothing is offered that the build does not contain or the machine cannot do.

## Rebuilding

The LGPL asks that anyone can rebuild the encoder. Today the natives are the JavaCPP Presets' own, and their build
script is at the address above. A build script of our own, for every platform, is planned and will be kept with
this source.
