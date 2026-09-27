# The ground as one texture: 32 px a tile over a 40 x 30 tile area whose tile (7, 8) is the world's (0, 0).
from PIL import Image
import numpy as np
PX, TW, TH, OX, OZ = 32, 40, 30, 7, 8
W, H = TW * PX, TH * PX
rs = np.random.RandomState(4)
def noise(cell, seed):
    r = np.random.RandomState(seed).rand(H // cell + 3, W // cell + 3)
    return np.asarray(Image.fromarray((r * 255).astype(np.uint8)).resize((W + 3 * cell, H + 3 * cell), Image.BICUBIC)).astype(float)[:H, :W] / 255
n = noise(160, 1) * 0.55 + noise(48, 2) * 0.3 + noise(12, 3) * 0.15
grass = np.array([[46, 86, 38], [58, 106, 44], [72, 124, 50], [88, 142, 58], [108, 160, 66], [132, 178, 78]], float)
q = np.clip((n - 0.18) / 0.64 * len(grass), 0, len(grass) - 1).astype(int)
img = grass[q]
# blades: single pixels a shade lighter or darker, and a few flowers
blades = rs.rand(H, W)
up = np.minimum(q + 1, len(grass) - 1); dn = np.maximum(q - 1, 0)
img[blades > 0.93] = grass[up][blades > 0.93]
img[blades < 0.05] = grass[dn][blades < 0.05]
for (col, k) in [((246, 214, 92), 260), ((236, 236, 226), 200), ((226, 120, 140), 90)]:
    ys, xs = rs.randint(0, H, k), rs.randint(0, W, k)
    for y, x in zip(ys, xs):
        img[y:y + 2, x:x + 2] = col
def tiles(x0, z0, x1, z1):
    return slice((z0 + OZ) * PX, (z1 + OZ) * PX), slice((x0 + OX) * PX, (x1 + OX) * PX)
def dirt(x0, z0, x1, z1):
    sy, sx = tiles(x0, z0, x1, z1)
    h, w = sy.stop - sy.start, sx.stop - sx.start
    d = noise(10, 7)[:h, :w] * 0.6 + rs.rand(h, w) * 0.4
    pal = np.array([[150, 104, 62], [176, 126, 76], [194, 144, 90], [210, 164, 108]], float)
    patch = pal[np.clip((d * len(pal)).astype(int), 0, len(pal) - 1)]
    # ragged grass edge: the outer 3 px keep the grass where the noise says so
    edge = np.zeros((h, w), bool); e = 3
    edge[:e] = edge[-e:] = True; edge[:, :e] = edge[:, -e:] = True
    keep = edge & (rs.rand(h, w) > 0.45)
    region = img[sy, sx]; region[~keep] = patch[~keep]; img[sy, sx] = region
def soil(x0, z0, w_, h_):
    # three furrows per plot, ridged: dark trough, mid, light crest
    sy, sx = tiles(x0, z0, x0 + w_, z0 + h_)
    h, w = sy.stop - sy.start, sx.stop - sx.start
    rows = np.arange(h) % PX
    ridge = np.select([rows < 5, rows < 12, rows < 24, rows < 29], [0, 1, 2, 1], 0)
    pal = np.array([[76, 48, 30], [102, 66, 40], [124, 84, 52]], float)
    patch = pal[ridge][:, None, :].repeat(w, 1) + (rs.rand(h, w, 1) - 0.5) * 14
    img[sy, sx] = patch
dirt(-7, 6, 33, 8)      # the lane, running off both edges
dirt(16, -8, 18, 6)     # the road in from the north
dirt(4, 5, 6, 6)        # the farmhouse's path down to the lane
for (x, z) in [(11, 9), (6, 9), (16, 9), (1, 9), (21, 9), (11, 13), (6, 13), (16, 13)]:
    soil(x, z, 4, 3)
Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save('ground.png')
print('ground', W, H)
