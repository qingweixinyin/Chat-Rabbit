import struct, glob, os
for p in sorted(glob.glob(r"E:\Claude official\chat rabbit\router\dist\chatrabbit*")):
    d=open(p,"rb").read(20)
    if d[:4]!=b"\x7fELF": print(os.path.basename(p), "非 ELF"); continue
    cls=d[4]; endian=d[5]; machine=struct.unpack("<H", d[18:20])[0]
    names={40:"ARM(32)",183:"AArch64(ARM64)"}
    print(f"{os.path.basename(p):26s} {'64位' if cls==2 else '32位'} {names.get(machine,machine)}")
