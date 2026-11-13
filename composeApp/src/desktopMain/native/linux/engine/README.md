# Linux torrent engine libraries

The Linux build keeps one `libnuvio_engine.so` per architecture so the x86_64
and arm64 packages come out of the same checkout:

```
engine/x86_64/libnuvio_engine.so
engine/aarch64/libnuvio_engine.so
```

`:composeApp:desktopJar` bundles only the library matching the build host and
logs a warning when that architecture has no engine build yet, so a missing
library degrades to "P2P streaming is not available" instead of failing the
release. Drop a new architecture in next to the existing one and it is picked
up automatically — no Gradle or workflow change needed.

Every library here must come from the same engine release as
`composeApp/libs/nuvio-engine-jvm-<version>.jar`; `DesktopEngineTest` asserts
the loaded runtime reports that version.

## Producing a library

The engine repository builds both Linux architectures (Docker required). The
build machine's own architecture is compiled and tested natively; the other one
is cross-compiled with the GNU toolchain and only its test suite runs under a
user-mode emulator, so both packages take about the same time:

```bash
./scripts/build-linux-packages.sh
```

That writes `platform/linux/dist/nuvio-engine-linux-<arch>.tar.gz`. Take the
versioned library out of the archive and drop it in here unversioned:

```bash
tar -xzf nuvio-engine-linux-aarch64.tar.gz
cp nuvio-engine-linux-aarch64/lib/libnuvio_engine.so.<version> \
  composeApp/src/desktopMain/native/linux/engine/aarch64/libnuvio_engine.so
```
