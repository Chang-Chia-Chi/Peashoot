extends RefCounted
## The paddock (docs/farm-growth.md): its fence and trough on the starting island, and the pasture
## each barn upgrade adds to it. The paddock sits on the island's east shore, so it grows east over
## the sea: each upgrade moves the fence out a stretch, on new land that rises as a bought plot's
## does, and the old east fence comes down so the herd walks on through. main.gd builds the island
## and calls on this for the paddock; life.gd keeps the herd [method inside] it.

## The paddock's fence corners on the starting island, in bricks.
const CORNER := Vector2i(60, 34)
const FAR := Vector2i(84, 50)
## The stone trough inside the north fence, which the herd walks round.
const TROUGH := Rect2i(76, 36, 6, 3)
## How far east each barn upgrade moves the fence, in bricks, and the most stretches the paddock
## takes: past three upgrades the barn keeps adding room, as its lean-to does, but the paddock is as
## big as it gets.
const STRETCH := 10
const MOST_STRETCHES := 3


## The paddock on the starting island: its fence and a stone trough of water.
static func paddock(world: Node3D) -> void:
	_fence(world, CORNER, FAR)
	var y: int = world.GROUND + 1
	var at := TROUGH.position
	world.box(at.x, y, at.y, TROUGH.size.x, 2, TROUGH.size.y, world.STONE)
	world.box(at.x + 1, y + 1, at.y + 1, TROUGH.size.x - 2, 1, 1, world.WATER)


## Where the fence's east side stands with [param stretches] of pasture, in bricks.
static func east(stretches: int) -> int:
	return FAR.x + STRETCH * stretches


## How far east the land and its sea reach with [param stretches] of pasture, in bricks, for the
## camera to take in; 0 for none, when the island's own width does.
static func width(stretches: int) -> float:
	if stretches == 0:
		return 0.0
	return east(stretches) + 12.0


## Where the herd keeps to with [param stretches] of pasture, in bricks: clear of the fence, and
## before the first stretch clear of the island's sandy south-east corner too.
static func inside(stretches: int) -> Rect2i:
	if stretches == 0:
		return Rect2i(62, 37, 15, 11)
	return Rect2i(62, 37, east(stretches) - 65, 11)


## How far out of the pasture's land a brick is: below 0 on it, 0 at its shore, as the world's
## coast is for the island. A rounded rectangle round the new fence that reaches back over the
## island's shore, so the two run together into one coast.
static func edge(stretches: int, x: int, z: int) -> float:
	var x0 := FAR.x - 8.0
	var x1 := east(stretches) + 3.0
	var z0 := CORNER.y - 2.0
	var z1 := FAR.y + 3.0
	var dx := (x - (x0 + x1) / 2.0) / ((x1 - x0) / 2.0)
	var dz := (z - (z0 + z1) / 2.0) / ((z1 - z0) / 2.0)
	var n := sin(x * 0.29 + z * 0.19) * 0.05 + cos(z * 0.23 - x * 0.13) * 0.05
	return pow(absf(dx), 6.0) + pow(absf(dz), 6.0) - 1.0 + n


## The pasture raised in [param world] for [param stretches] barn upgrades: land out of the sea and
## over the island's beach east of the paddock, sea past the island's own out to [param deep] bricks
## towards the camera, and the fence carried round it with its old east side taken down.
static func raise(world: Node3D, stretches: int, deep: int) -> void:
	if stretches == 0:
		return
	var dig := RandomNumberGenerator.new()
	dig.seed = 91
	var far := east(stretches)
	for x in range(CORNER.x, far + 17):
		for z in range(-14, deep):
			var shore := edge(stretches, x, z)
			if shore < 0 and world.top(x, z) < world.Land.height(world, shore):
				world.Land.rise(world, dig, x, z, shore)
			elif x >= world.W + 16:
				world.Land.sea(world, dig, x, z, minf(shore, world.coast(x, z)))
	_fence(world, CORNER, Vector2i(far, FAR.y))
	for z in range(CORNER.y + 1, FAR.y):
		for y in range(world.GROUND + 1, world.GROUND + 4):
			world.bricks.erase(Vector3i(FAR.x, y, z))


## A fence round [param from] to [param to]: a post every third brick, and a rail between.
static func _fence(world: Node3D, from: Vector2i, to: Vector2i) -> void:
	for x in range(from.x, to.x + 1):
		for z in range(from.y, to.y + 1):
			if x != from.x and x != to.x and z != from.y and z != to.y:
				continue
			var y: int = world.GROUND + 1
			if (x + z) % 3 == 0:
				world.box(x, y, z, 1, 3, 1, world.WOOD_DARK)
			else:
				world.put(x, y + 1, z, world.WOOD)
