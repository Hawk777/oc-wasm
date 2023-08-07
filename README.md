Overview
========

OC-Wasm is a CPU architecture for [OpenComputers](https://oc.cil.li/) which
executes [WebAssembly](https://webassembly.org/) binaries.


Getting Started
===============

To get a basic “hello world”-type application running with OC-Wasm, you’ll need
to follow a few steps:

* Build a computer. OC-Wasm doesn’t add any new items; instead, you use the
  standard OpenComputers items, just as if you were building a Lua computer.
  After crafting the CPU or APU, hold it in your hand and Shift-Right-Click to
  switch architectures—just like you would to switch between Lua 5.2 and
  5.3—and you should see WebAssembly in the list.
* All computers need a BIOS, and WebAssembly computers are no different. If
  your program is very small (≤4 kiB), you can put it directly on an EEPROM and
  use it as a BIOS directly. Usually, though, your program will be too big to
  fit on an EEPROM, so you’ll want a BIOS which can load your program from
  disk. [OC-Wasm-BIOS](https://gitlab.com/Hawk777/oc-wasm-bios/) does just
  that: it loads a program from `/init.wasm` on a disk (just like the Lua BIOS
  loads a program from `/init.lua`). Since there’s no craftable item that comes
  with a BIOS, you’ll need to download the BIOS onto an existing computer
  (presumably running Lua, e.g. via `wget`) and flash it onto an EEPROM (e.g.
  via `flash`). Here's a one-liner that does that:
  `wget https://gitlab.com/api/v4/projects/27476164/jobs/artifacts/main/raw/packed.wasm?job=Compile -O bios.wasm && flash bios.wasm OC-Wasm-BIOS`
* Create your application. You’ll probably do this outside Minecraft, using
  regular software development tools. Once you’ve compiled a `.wasm` file, name
  it `init.wasm` and place it in the root directory of a hard drive or floppy
  disk. This can be done a few different ways:
  * Upload your program to the Internet, put the disk in a working computer,
    download the file to it with `wget`, and move the disk to the new computer.
  * Place the file directly in the proper place in your Minecraft save game
    directory, if you have access to it. You probably want to set
    `bufferChanges` to `false` in the `filesystem` section of your
    OpenComputers config file if you’re regularly using this method.
* Boot your computer!


Working in Rust
===============

A number of crates are available to make writing OC-Wasm programs in Rust
ergonomic:

* [OC-Wasm-Cassette](https://crates.io/crates/oc-wasm-cassette) provides an
  easy way to use the Cassette async executor to run an `async fn` as your main
  function.
* [OC-Wasm-Sys](https://crates.io/crates/oc-wasm-sys) is a set of raw FFI
  bindings. You probably don’t want to use this crate directly, but it
  underpins all the other ones.
* [OC-Wasm-Safe](https://crates.io/crates/oc-wasm-safe) is a set of wrappers
  around OC-Wasm-Sys that provide memory safety, safe handling of opaque value
  descriptors, and mutual exclusion over system calls that cannot be invoked
  multiple times simultaneously.
* [OC-Wasm-Futures](https://crates.io/crates/oc-wasm-futures) is a set of async
  futures that make it easier to write an OC-Wasm program using `async` and
  `await`.
* [OC-Wasm-OpenComputers](https://crates.io/crates/oc-wasm-opencomputers) is a
  set of high-level wrappers around the component APIs supported by ordinary
  OpenComputers (such as the redstone API, the filesystem API, the GPU API,
  etc.).

As of this writing, `OC-Wasm-OpenComputers` is incomplete, and there are no
crates for high-level wrappers around other mods that can interact with
OpenComputers (e.g. via the adapter block); however, community contributions
(in the form of merge requests to `OC-Wasm-OpenComputers` or new crates for
content from mods) are very welcome!


Working in Zig
===============

AmandaC from the OpenComputers community has started early work on [a
repository with a Zig library and a few small examples for
OC-Wasm](https://git.camnet.site/amandac/ocwasm-zig).


Technical Details
=================

If you want to work in a language which doesn’t already have support libraries,
or you’re just curious how things work under the hood, please have a look at
[the OC-Wasm Javadoc](https://hawk777.gitlab.io/oc-wasm/).
