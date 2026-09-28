extends RefCounted
## The plots bought beyond the starting island (docs/farm-growth.md): where each lies, the land it
## raises out of the sea, and its four beds. Rows of three across the front of the island, the first
## row overlapping the south shore so the new land joins the old. main.gd builds the island and
## calls on this for the rest, with dice of its own, so the island around the plots is the island
## it always was.

const PLOT_W := 24
const PLOT_D := 24
const PLOT_X0 := 20
const PLOT_Z0 := 56
const PLOTS_A_ROW := 3
## Where each of a plot's four beds lies from its corner.
const PLOT_BEDS: Array[Vector2i] = [
	Vector2i(1, 3), Vector2i(13, 3), Vector2i(1, 13), Vector2i(13, 13)
]


## The north-west corner of plot [param k], in bricks.
static func corner(k: int) -> Vector2i:
	return Vector2i(
		PLOT_X0 + (k % PLOTS_A_ROW) * (PLOT_W + 2), PLOT_Z0 + (k / PLOTS_A_ROW) * PLOT_D
	)


## How far the land reaches towards the camera with [param plots] bought, in bricks; 0 for none.
static func front(plots: int) -> float:
	if plots == 0:
		return 0.0
	return PLOT_Z0 + ceili(plots / float(PLOTS_A_ROW)) * PLOT_D


## The beds on the plots bought, four a plot, first plot first.
static func beds(plots: int) -> Array[Vector2i]:
	var all: Array[Vector2i] = []
	for k in plots:
		for bed in PLOT_BEDS:
			all.append(corner(k) + bed)
	return all


## How far out of plot [param k]'s land a brick is: below 0 on it, 0 at its shore, as the world's
## coast is for the island. A rounded square a little wider than the plot, so neighbouring plots
## and the island's shore run together into one coast.
static func edge(k: int, x: int, z: int) -> float:
	var c := corner(k)
	var dx := (x - c.x - PLOT_W / 2.0) / (PLOT_W / 2.0 + 1)
	var dz := (z - c.y - PLOT_D / 2.0) / (PLOT_D / 2.0 + 1)
	var n := sin(x * 0.31 + z * 0.17) * 0.05 + cos(z * 0.27 - x * 0.11) * 0.05
	return pow(abs(dx), 6.0) + pow(abs(dz), 6.0) - 1.0 + n


## The plots' land raised in [param world] where the sea was, sea past the old sea's edge, and each
## plot's four beds.
static func raise(world: Node3D, plots: int) -> void:
	if plots == 0:
		return
	var dig := RandomNumberGenerator.new()
	dig.seed = 77
	for x in range(-16, world.W + 16):
		for z in range(PLOT_Z0 - 4, int(front(plots)) + 14):
			var at: float = world.coast(x, z)
			var shore: float = at
			for k in plots:
				shore = minf(shore, edge(k, x, z))
			if shore >= 0:
				if z >= world.D + 14:
					_sea(world, dig, x, z, shore)
			elif at >= 0:
				_land(world, dig, x, z, shore)
	for bed in beds(plots):
		world.raised_bed(bed)


## A brick of sea past where the island's own sea stops, foamy near the shore.
static func _sea(world: Node3D, dig: RandomNumberGenerator, x: int, z: int, shore: float) -> void:
	var c: Color = world.WATER
	if shore < 0.07 and dig.randf() < 0.75:
		c = world.FOAM
	elif shore < 0.16 and dig.randf() < 0.35:
		c = world.FOAM_BLUE
	world.put(x, 0, z, c)
	if shore < 0.9:
		world.shore[Vector3i(x, 0, z)] = shore


## A column of new land where the sea was, sandy at its shore as the island is.
static func _land(world: Node3D, dig: RandomNumberGenerator, x: int, z: int, shore: float) -> void:
	for y in range(0, world.GROUND + 6):
		world.bricks.erase(Vector3i(x, y, z))
		world.slopes.erase(Vector3i(x, y, z))
	world.shore.erase(Vector3i(x, 0, z))
	var height: int = world.GROUND
	if shore > -0.1:
		height = 1
	elif shore > -0.2:
		height = 2
	for y in range(0, height):
		world.put(x, y, z, world.EARTH)
	var c: Color = world.GRASS_LIGHT if dig.randf() < 0.3 else world.GRASS
	if height < world.GROUND:
		c = world.SAND if dig.randf() > 0.2 else world.SAND_DARK
	world.put(x, height, z, c)
