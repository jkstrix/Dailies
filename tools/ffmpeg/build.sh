#!/usr/bin/env bash
# Builds the video encoder Dailies carries (specification EXP-1, EXP-8): FFmpeg's ffmpeg and ffprobe programs, in an
# LGPL configuration, for one platform at a time, and packs each platform as a jar of its own plus one jar of all.
#
# This script is the source of the encoder as the LGPL asks: anyone can rebuild the programs from the pinned sources in
# sources.lock with it, and put their own build in place of the one in Dailies (Dailies runs the programs beside the
# game and links against nothing). The GitHub Actions workflow .github/workflows/ffmpeg.yml runs it on every platform.
#
#   ./build.sh lock                 download every source whose sha256 in sources.lock is "-", and write its sha256 there
#   ./build.sh build <platform>     fetch, check and build everything for one platform into out/platforms/<platform>
#   ./build.sh verify <platform>    run the built programs and check their configuration (must run on that platform)
#   ./build.sh pack                 pack every platform in out/platforms as out/jars/dailies-ffmpeg-<version>-<platform>.jar
#                                   and all of them as out/jars/dailies-ffmpeg-<version>-all.jar
#
# Platforms, and where each is built:
#   windows-x86_64   on Linux x86_64, with the mingw-w64 cross compilers (posix threads)
#   linux-x86_64     on Linux x86_64        linux-arm64    on Linux arm64
#   macosx-arm64     on macOS arm64         macosx-x86_64  on macOS x86_64
#
# What is in it: SVT-AV1 and libaom (AV1), libvpx (VP9), Opus, and FFmpeg's own AAC, ProRes and DNxHR, in software;
# NVIDIA NVENC, AMD AMF, Intel Quick Sync (oneVPL) and Media Foundation on Windows; NVENC, VA-API (AMD and Intel),
# Quick Sync and AMF on Linux; VideoToolbox on macOS. H.264 and H.265 come from those hardware encoders only.
# What is never in it: --enable-gpl, --enable-nonfree, x264, x265, OpenH264. "verify" refuses a build that has any.
#
# Needs: bash, curl, tar, xz, make, cmake, ninja, meson (Linux), nasm (x86_64), pkg-config, a C and C++ compiler,
# patchelf (Linux), zip (pack), sha256sum or shasum. The workflow installs them.
#
# Environment: DAILIES_FFMPEG_WORK (default tools/ffmpeg/work) and DAILIES_FFMPEG_OUT (default tools/ffmpeg/out);
# JOBS for the number of parallel compiles.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCK="$HERE/sources.lock"
WORK="${DAILIES_FFMPEG_WORK:-$HERE/work}"
OUT="${DAILIES_FFMPEG_OUT:-$HERE/out}"
DOWNLOADS="$WORK/downloads"
# A fixed moment for every file time the build or the jars record, so two builds of the same sources match
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1767225600}"
export TZ=UTC LC_ALL=C

say() { printf '\n== %s\n' "$*" >&2; }
die() { printf 'build.sh: %s\n' "$*" >&2; exit 1; }

jobs_count() {
    if [ -n "${JOBS:-}" ]; then echo "$JOBS"; return; fi
    if command -v nproc >/dev/null 2>&1; then nproc; else sysctl -n hw.ncpu; fi
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# The lines of sources.lock, without comments: name|version|url|sha256
lock_lines() { grep -v '^[[:space:]]*#' "$LOCK" | grep -v '^[[:space:]]*$'; }

lock_field() {  # lock_field <name> <field number>
    lock_lines | awk -F'|' -v n="$1" -v f="$2" '$1 == n { print $f }'
}

archive_of() { local url; url="$(lock_field "$1" 3)"; echo "$DOWNLOADS/$1-$(lock_field "$1" 2).tar.${url##*.tar.}"; }

# --- Sources ---

download() {  # download <name>: fetches into downloads/, checks the sha256 unless it is still "-"
    local name="$1" url want file got
    url="$(lock_field "$name" 3)"
    want="$(lock_field "$name" 4)"
    [ -n "$url" ] || die "no source called $name in sources.lock"
    mkdir -p "$DOWNLOADS"
    file="$(archive_of "$name")"
    if [ ! -f "$file" ]; then
        say "downloading $name from $url"
        curl -fL --retry 3 -o "$file.part" "$url"
        mv "$file.part" "$file"
    fi
    got="$(sha256_of "$file")"
    if [ "$want" = "-" ]; then
        echo "$got"
        return 0
    fi
    [ "$got" = "$want" ] || die "$name: the download's sha256 is $got, and sources.lock pins $want. Not building from it"
    echo "$got"
}

cmd_lock() {
    local tmp name sum
    tmp="$(mktemp)"
    while IFS= read -r line; do
        case "$line" in
            \#*|'') echo "$line" ;;
            *)
                name="${line%%|*}"
                if [ "${line##*|}" = "-" ]; then
                    sum="$(download "$name")"
                    echo "${line%|*}|$sum"
                    say "$name pinned at $sum"
                else
                    echo "$line"
                fi
                ;;
        esac
    done < "$LOCK" > "$tmp"
    mv "$tmp" "$LOCK"
}

unpack() {  # unpack <name>: a fresh copy of the source in the platform's build folder; prints its path
    local name="$1" dir
    dir="$BUILD/$name"
    rm -rf "$dir"
    mkdir -p "$dir"
    tar -xf "$(archive_of "$name")" -C "$dir" --strip-components=1
    echo "$dir"
}

# --- Platforms ---

setup_platform() {
    PLATFORM="$1"
    BUILD="$WORK/build/$PLATFORM"
    PREFIX="$WORK/prefix/$PLATFORM"
    DEST="$OUT/platforms/$PLATFORM"
    EXE=""
    CROSS=""
    HOST=""
    CMAKE_PLATFORM=()
    FF_PLATFORM=()
    local os arch
    os="$(uname -s)"
    arch="$(uname -m)"
    case "$PLATFORM" in
        windows-x86_64)
            [ "$os" = Linux ] && [ "$arch" = x86_64 ] || die "windows-x86_64 is cross-built on Linux x86_64"
            HOST=x86_64-w64-mingw32
            CROSS="$HOST-"
            EXE=".exe"
            CMAKE_PLATFORM=(-DCMAKE_SYSTEM_NAME=Windows -DCMAKE_SYSTEM_PROCESSOR=AMD64
                -DCMAKE_C_COMPILER="$HOST-gcc" -DCMAKE_CXX_COMPILER="$HOST-g++" -DCMAKE_RC_COMPILER="$HOST-windres"
                -DCMAKE_FIND_ROOT_PATH="$PREFIX" -DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER
                -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY)
            FF_PLATFORM=(--target-os=mingw32 --arch=x86_64 --enable-cross-compile --cross-prefix="$CROSS"
                --enable-w32threads --enable-ffnvcodec --enable-nvenc --enable-amf --enable-libvpl
                --enable-mediafoundation --enable-d3d11va --enable-dxva2
                --extra-ldflags=-static --extra-ldflags=-static-libgcc --extra-ldflags=-static-libstdc++
                --extra-libs=-lstdc++)
            ;;
        linux-x86_64|linux-arm64)
            [ "$os" = Linux ] || die "$PLATFORM is built on Linux"
            case "$PLATFORM/$arch" in linux-x86_64/x86_64|linux-arm64/aarch64) ;; *) die "$PLATFORM is built natively, and this machine is $arch" ;; esac
            FF_PLATFORM=(--enable-pthreads --enable-ffnvcodec --enable-nvenc --enable-vaapi --extra-libs=-lstdc++ --extra-libs=-ldl --extra-libs=-lm)
            if [ "$PLATFORM" = linux-x86_64 ]; then
                # Intel Quick Sync and AMD AMF exist on x86_64 machines only
                FF_PLATFORM+=(--enable-libvpl --enable-amf)
            fi
            ;;
        macosx-arm64|macosx-x86_64)
            [ "$os" = Darwin ] || die "$PLATFORM is built on macOS"
            case "$PLATFORM/$arch" in macosx-arm64/arm64|macosx-x86_64/x86_64) ;; *) die "$PLATFORM is built natively, and this machine is $arch" ;; esac
            export MACOSX_DEPLOYMENT_TARGET=11.0
            CMAKE_PLATFORM=(-DCMAKE_OSX_DEPLOYMENT_TARGET=11.0 -DCMAKE_OSX_ARCHITECTURES="${arch}")
            FF_PLATFORM=(--enable-pthreads --enable-videotoolbox --enable-audiotoolbox
                --extra-cflags=-mmacosx-version-min=11.0 --extra-ldflags=-mmacosx-version-min=11.0)
            ;;
        *) die "no platform called $PLATFORM: windows-x86_64, linux-x86_64, linux-arm64, macosx-arm64 or macosx-x86_64" ;;
    esac
    mkdir -p "$BUILD" "$PREFIX/include" "$PREFIX/lib/pkgconfig"
    # What was built here is found first. FFmpeg is configured with --disable-autodetect, so nothing of the machine's own
    # is ever taken in unless named above; on Linux the system's folders are also searched, for libdrm, which libva needs
    export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
    case "$PLATFORM" in
        linux-*) PKG_CONFIG_LIBDIR="$PKG_CONFIG_LIBDIR:/usr/lib/$(gcc -dumpmachine)/pkgconfig:/usr/lib/pkgconfig:/usr/share/pkgconfig" ;;
    esac
    unset PKG_CONFIG_PATH
    JOBS_N="$(jobs_count)"
}

has() { case " ${FF_PLATFORM[*]} " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

cmake_build() {  # cmake_build <source dir> <extra cmake arguments...>
    local src="$1"; shift
    cmake -S "$src" -B "$src/_build" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" \
        -DCMAKE_INSTALL_LIBDIR=lib -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON "${CMAKE_PLATFORM[@]}" "$@"
    cmake --build "$src/_build" -j "$JOBS_N"
    cmake --install "$src/_build"
}

# --- The libraries ---

build_zlib() {
    local src; src="$(unpack zlib)"
    (cd "$src" && CROSS_PREFIX="$CROSS" ./configure --static --prefix="$PREFIX" && make -j "$JOBS_N" && make install)
}

build_opus() {
    local src; src="$(unpack opus)"
    (cd "$src" && ./configure ${HOST:+--host=$HOST} --prefix="$PREFIX" --disable-shared --enable-static --with-pic \
        --disable-doc --disable-extra-programs && make -j "$JOBS_N" && make install)
}

build_libvpx() {
    local src target as=()
    src="$(unpack libvpx)"
    case "$PLATFORM" in
        windows-x86_64) target=x86_64-win64-gcc ;;
        linux-x86_64) target=x86_64-linux-gcc ;;
        linux-arm64) target=arm64-linux-gcc ;;
        macosx-arm64) target=arm64-darwin20-gcc ;;
        macosx-x86_64) target=x86_64-darwin20-gcc ;;
    esac
    case "$PLATFORM" in *x86_64) as=(--as=nasm) ;; esac
    (cd "$src" && CROSS="$CROSS" ./configure --target="$target" --prefix="$PREFIX" --libdir="$PREFIX/lib" "${as[@]}" \
        --disable-examples --disable-tools --disable-docs --disable-unit-tests --disable-shared --enable-static \
        --enable-pic --enable-vp9-highbitdepth && make -j "$JOBS_N" && make install)
}

build_libaom() {
    local src extra=()
    src="$(unpack libaom)"
    if [ "$PLATFORM" = windows-x86_64 ]; then
        # libaom's own description of the mingw compilers, in place of the generic one
        extra=(-DCMAKE_TOOLCHAIN_FILE="$src/build/cmake/toolchains/x86_64-mingw-gcc.cmake")
    fi
    cmake_build "$src" "${extra[@]}" -DENABLE_DOCS=0 -DENABLE_EXAMPLES=0 -DENABLE_TESTS=0 -DENABLE_TESTDATA=0 \
        -DENABLE_TOOLS=0 -DCONFIG_TUNE_VMAF=0 -DCONFIG_TUNE_BUTTERAUGLI=0
}

build_svtav1() {
    local src; src="$(unpack svt-av1)"
    cmake_build "$src" -DBUILD_APPS=OFF -DBUILD_DEC=OFF -DBUILD_TESTING=OFF -DSVT_AV1_LTO=OFF
}

build_nv_codec_headers() {
    local src; src="$(unpack nv-codec-headers)"
    (cd "$src" && make PREFIX="$PREFIX" install)
}

build_amf() {
    local src; src="$(unpack amf)"
    rm -rf "$PREFIX/include/AMF"
    mkdir -p "$PREFIX/include/AMF"
    cp -R "$src/amf/public/include/." "$PREFIX/include/AMF/"
    # Kept for the licence copy: the headers are all that is used
    mkdir -p "$BUILD/amf-licence" && cp "$src"/LICENSE* "$BUILD/amf-licence/" 2>/dev/null || true
}

build_libvpl() {
    local src; src="$(unpack libvpl)"
    cmake_build "$src" -DBUILD_TOOLS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_TESTS=OFF -DINSTALL_EXAMPLES=OFF -DINSTALL_DEV=ON
}

build_libva() {
    local src; src="$(unpack libva)"
    # Shared, and shipped beside ffmpeg: libva finds the machine's own drivers, in any of the usual places
    meson setup "$src/_build" "$src" --prefix="$PREFIX" --libdir=lib --buildtype=release -Ddefault_library=shared \
        -Dwith_x11=no -Dwith_glx=no -Dwith_wayland=no -Denable_docs=false \
        -Ddriverdir=/usr/lib/x86_64-linux-gnu/dri:/usr/lib/aarch64-linux-gnu/dri:/usr/lib64/dri:/usr/lib/dri:/usr/local/lib/dri
    ninja -C "$src/_build" install
}

build_ffmpeg() {
    local src; src="$(unpack ffmpeg)"
    local configure=(--prefix="$PREFIX" --pkg-config=pkg-config --pkg-config-flags=--static --extra-version=dailies
        --disable-autodetect --disable-debug --disable-doc --disable-ffplay --disable-shared --enable-static
        --extra-cflags="-I$PREFIX/include" --extra-ldflags="-L$PREFIX/lib"
        --enable-zlib --enable-libopus --enable-libvpx --enable-libaom --enable-libsvtav1
        "${FF_PLATFORM[@]}")
    # Said in so many words, so no change of FFmpeg's defaults can bring any of these in
    for forbidden in --enable-gpl --enable-nonfree --enable-version3 --enable-libx264 --enable-libx265 --enable-libopenh264; do
        case " ${configure[*]} " in *" $forbidden "*) die "the configuration asks for $forbidden, which Dailies never ships" ;; esac
    done
    printf '%s\n' "${configure[@]}" > "$BUILD/ffmpeg-configure.txt"
    (cd "$src" && ./configure "${configure[@]}" && make -j "$JOBS_N" && make install)
}

licences() {  # Each library's own licence files, from its own source, into out/licenses/<name>
    local name src dir found
    for name in ffmpeg zlib opus libvpx libaom svt-av1 nv-codec-headers amf libvpl libva; do
        src="$BUILD/$name"
        [ -d "$src" ] || continue
        dir="$OUT/licenses/$name"
        rm -rf "$dir"
        mkdir -p "$dir"
        found=0
        for file in "$src"/LICENSE* "$src"/LICENCE* "$src"/COPYING* "$src"/PATENTS* "$src"/AUTHORS; do
            [ -f "$file" ] || continue
            cp "$file" "$dir/"
            found=1
        done
        if [ "$name" = nv-codec-headers ]; then
            # Its licence is the MIT notice at the head of each header
            sed -n '1,/\*\//p' "$src/include/ffnvcodec/nvEncodeAPI.h" > "$dir/LICENSE-from-nvEncodeAPI.h.txt"
            found=1
        fi
        [ "$found" = 1 ] || die "no licence file found in the $name source"
    done
    mkdir -p "$OUT/notices"
    cp "$HERE"/notices/*.txt "$OUT/notices/"
}

collect() {
    rm -rf "$DEST"
    mkdir -p "$DEST"
    local strip="${CROSS}strip"
    for program in ffmpeg ffprobe; do
        cp "$PREFIX/bin/$program$EXE" "$DEST/"
        "$strip" "$DEST/$program$EXE" || true
    done
    case "$PLATFORM" in
        linux-*)
            for lib in libva.so.2 libva-drm.so.2; do
                cp -L "$PREFIX/lib/$lib" "$DEST/$lib"
                strip --strip-unneeded "$DEST/$lib" || true
            done
            # The programs find the libva shipped beside them first
            for file in "$DEST"/ffmpeg "$DEST"/ffprobe "$DEST"/libva*.so.2; do
                patchelf --set-rpath '$ORIGIN' "$file"
            done
            ;;
    esac
    {
        echo "platform: $PLATFORM"
        echo "built: $(date -u -d "@$SOURCE_DATE_EPOCH" 2>/dev/null || date -u -r "$SOURCE_DATE_EPOCH")"
        echo "sources:"
        lock_lines | sed 's/^/  /'
        echo "ffmpeg configure:"
        sed 's/^/  /' "$BUILD/ffmpeg-configure.txt"
    } > "$OUT/platforms/$PLATFORM.buildinfo.txt"
}

cmd_build() {
    setup_platform "$1"
    for name in $(lock_lines | cut -d'|' -f1); do
        [ "$(lock_field "$name" 4)" != "-" ] || die "$name is not pinned in sources.lock: run ./build.sh lock first"
        download "$name" >/dev/null
    done
    say "building for $PLATFORM in $BUILD"
    build_zlib
    build_opus
    build_libvpx
    build_libaom
    build_svtav1
    if has --enable-nvenc; then build_nv_codec_headers; fi
    if has --enable-amf; then build_amf; fi
    if has --enable-libvpl; then build_libvpl; fi
    if has --enable-vaapi; then build_libva; fi
    build_ffmpeg
    licences
    collect
    say "built $DEST"
}

# --- Checks ---

cmd_verify() {
    local dir="$OUT/platforms/$1" exe=""
    case "$1" in windows-*) exe=".exe" ;; esac
    local ffmpeg="$dir/ffmpeg$exe" ffprobe="$dir/ffprobe$exe"
    [ -x "$ffmpeg" ] && [ -x "$ffprobe" ] || die "no built programs in $dir"
    local conf encoders
    conf="$("$ffmpeg" -hide_banner -buildconf)"
    encoders="$("$ffmpeg" -hide_banner -encoders)"
    "$ffprobe" -hide_banner -version >/dev/null
    for forbidden in --enable-gpl --enable-nonfree --enable-libx264 --enable-libx265 --enable-libopenh264; do
        if grep -q -- "$forbidden" <<<"$conf"; then die "the build was configured with $forbidden"; fi
    done
    "$ffmpeg" -hide_banner -L | grep -q "Lesser General Public" || die "the build does not say it is under the LGPL"
    local want=(prores_ks dnxhd aac libopus libsvtav1 libaom-av1 libvpx-vp9 png pcm_s16le)
    case "$1" in
        windows-x86_64) want+=(h264_nvenc hevc_nvenc av1_nvenc h264_amf hevc_amf av1_amf h264_qsv hevc_qsv av1_qsv h264_mf hevc_mf) ;;
        linux-x86_64) want+=(h264_nvenc hevc_nvenc av1_nvenc h264_vaapi hevc_vaapi av1_vaapi h264_qsv hevc_qsv av1_qsv h264_amf hevc_amf av1_amf) ;;
        linux-arm64) want+=(h264_nvenc hevc_nvenc av1_nvenc h264_vaapi hevc_vaapi av1_vaapi) ;;
        macosx-*) want+=(h264_videotoolbox hevc_videotoolbox prores_videotoolbox aac_at) ;;
    esac
    for encoder in "${want[@]}"; do
        grep -qE "^ [A-Z.]{6} $encoder( |$)" <<<"$encoders" || die "the build has no $encoder encoder"
    done
    for encoder in libx264 libx264rgb libx265 libopenh264; do
        if grep -qE "^ [A-Z.]{6} $encoder( |$)" <<<"$encoders"; then die "the build has the $encoder encoder, which Dailies never ships"; fi
    done
    # A second of each software codec, so a library that is in the build but broken is caught here and not by a player
    local tmp; tmp="$(mktemp -d)"
    "$ffmpeg" -hide_banner -v error -f lavfi -i testsrc2=size=320x180:rate=30 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 1 \
        -c:v libsvtav1 -c:a libopus "$tmp/a.webm"
    "$ffmpeg" -hide_banner -v error -f lavfi -i testsrc2=size=320x180:rate=30 -t 1 -c:v libvpx-vp9 "$tmp/b.webm"
    "$ffmpeg" -hide_banner -v error -f lavfi -i testsrc2=size=320x180:rate=30 -f lavfi -i sine=sample_rate=48000 -t 1 \
        -c:v prores_ks -profile:v 3 -c:a aac "$tmp/c.mov"
    "$ffmpeg" -hide_banner -v error -f lavfi -i testsrc2=size=1280x720:rate=30 -t 1 -c:v dnxhd -profile:v dnxhr_hq -pix_fmt yuv422p "$tmp/d.mov"
    "$ffprobe" -v error -count_frames -select_streams v:0 -show_entries stream=nb_read_frames -of csv=p=0 "$tmp/a.webm" | grep -q '^30' \
        || die "an AV1 file made by this build does not read back as 30 frames"
    rm -rf "$tmp"
    say "$1: LGPL, no GPL or non-free parts, ${#want[@]} encoders present, none forbidden, software codecs work"
}

# --- Jars ---

cmd_pack() {
    local version jars stage platform dir
    version="$(lock_field ffmpeg 2)"
    jars="$OUT/jars"
    rm -rf "$jars"
    mkdir -p "$jars"
    [ -d "$OUT/platforms" ] || die "nothing built in $OUT/platforms"
    local platforms=()
    for dir in "$OUT"/platforms/*/; do platforms+=("$(basename "$dir")"); done
    [ "${#platforms[@]}" -gt 0 ] || die "nothing built in $OUT/platforms"
    for platform in "${platforms[@]}" all; do
        stage="$WORK/stage/$platform"
        rm -rf "$stage"
        mkdir -p "$stage/META-INF"
        printf 'Manifest-Version: 1.0\nImplementation-Title: FFmpeg for Dailies (LGPL), %s\nImplementation-Version: %s\n' "$platform" "$version" > "$stage/META-INF/MANIFEST.MF"
        local members=("$platform")
        [ "$platform" = all ] && members=("${platforms[@]}")
        for member in "${members[@]}"; do
            mkdir -p "$stage/dailies/ffmpeg/$member"
            cp "$OUT/platforms/$member"/* "$stage/dailies/ffmpeg/$member/"
            # Passing through CI artifacts loses the execute bit; the programs need it on Linux and macOS
            chmod 755 "$stage/dailies/ffmpeg/$member"/ffmpeg* "$stage/dailies/ffmpeg/$member"/ffprobe*
            cp "$OUT/platforms/$member.buildinfo.txt" "$stage/META-INF/BUILDINFO-$member.txt"
        done
        cp -R "$OUT/licenses" "$stage/META-INF/licenses"
        cp -R "$OUT/notices" "$stage/META-INF/notices"
        cp "$LOCK" "$stage/META-INF/sources.lock"
        printf '%s\n' "These programs were built by tools/ffmpeg/build.sh in the Dailies source, from the sources pinned in" \
            "META-INF/sources.lock. Run it to rebuild them; any FFmpeg build can replace them (see META-INF/notices/ffmpeg.txt)." > "$stage/META-INF/SOURCE.txt"
        # The same bytes from the same files: fixed times, a fixed order, no extra fields
        find "$stage" -exec touch -h -d "@$SOURCE_DATE_EPOCH" {} + 2>/dev/null || find "$stage" -exec touch -h -t 202601010000 {} +
        (cd "$stage" && { echo META-INF/MANIFEST.MF; find . -type f ! -path ./META-INF/MANIFEST.MF | sed 's|^\./||' | sort; } \
            | zip -X -D -q "$jars/dailies-ffmpeg-$version-$platform.jar" -@)
    done
    cp -R "$OUT/licenses" "$jars/licenses"
    cp -R "$OUT/notices" "$jars/notices"
    (cd "$jars" && for jar in *.jar; do echo "$(sha256_of "$jar")  $jar"; done > SHA256SUMS)
    say "packed: $(cd "$jars" && ls *.jar | tr '\n' ' ')"
}

case "${1:-}" in
    lock) cmd_lock ;;
    build) [ -n "${2:-}" ] || die "build which platform?"; cmd_build "$2" ;;
    verify) [ -n "${2:-}" ] || die "verify which platform?"; cmd_verify "$2" ;;
    pack) cmd_pack ;;
    *) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
