import struct, glob, os
for p in sorted(glob.glob(r"E:\Claude official\chat rabbit\router\dist\chatrabbit*")):
    if not os.path.isfile(p): continue
    d=open(p,"rb").read(20)
    if d[:4]!=b"\x7fELF":
        print(f"{os.path.basename(p):28s} 非 ELF"); continue
    cls=d[4]; machine=struct.unpack("<H", d[18:20])[0]
    names={40:"ARM 32-bit",183:"AArch64 (ARM64)"}
    print(f"{os.path.basename(p):28s} {os.path.getsize(p)/1048576:.2f} MB  {'64-bit' if cls==2 else '32-bit'} {names.get(machine,machine)}")
