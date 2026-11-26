# Writes Beat This! small0's weights (github.com/CPJKU/beat_this, MIT) as float16 in the plain format BeatNet reads:
#   python3 tools/beat_this_export.py small0.ckpt core/src/jvmMain/resources/beat_this_small.bin (needs torch)
import struct, sys, torch
ck = torch.load(sys.argv[1], map_location="cpu", weights_only=True)
sd = {k[len("model."):]: v for k, v in ck["state_dict"].items() if not k.endswith("num_batches_tracked")}
with open(sys.argv[2], "wb") as f:
    f.write(b"BT01"); f.write(struct.pack("<i", len(sd)))
    for k, v in sd.items():
        n = k.encode(); f.write(struct.pack("<i", len(n))); f.write(n)
        f.write(struct.pack("<i", v.dim()))
        for d in v.shape: f.write(struct.pack("<i", d))
        f.write(v.to(torch.float16).contiguous().numpy().tobytes())
print(len(sd), "tensors")
