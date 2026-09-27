extends Node3D

const B := 0.25
const H := 0.2
const W := 96
const D := 64
const GROUND := 3
## How tall a farmer stands, in world units: a little under two tiles.
const FARMER_HEIGHT := 1.9
## Which way the camera looks from, so a farmer can face it.
const CAMERA_YAW := 28.0

const GRASS := Color(0.3, 0.56, 0.16)
const GRASS_LIGHT := Color(0.44, 0.66, 0.18)
const SAND := Color(0.9, 0.8, 0.58)
const SAND_DARK := Color(0.8, 0.68, 0.46)
const EARTH := Color(0.62, 0.46, 0.3)
const SOIL := Color(0.36, 0.22, 0.13)
const PATH := Color(0.86, 0.76, 0.56)
const WATER := Color(0.06, 0.3, 0.72)
const FOAM := Color(0.94, 0.97, 1.0)
const FOAM_BLUE := Color(0.6, 0.78, 0.96)
const TRUNK := Color(0.4, 0.25, 0.14)
const WOOD := Color(0.6, 0.36, 0.18)
const WOOD_DARK := Color(0.4, 0.22, 0.11)
const LOG := Color(0.55, 0.33, 0.17)
const ROOF := Color(0.78, 0.22, 0.12)
const STONE := Color(0.58, 0.58, 0.6)
const STONE_DARK := Color(0.4, 0.41, 0.44)
const WHITE := Color(0.95, 0.95, 0.93)
const RED := Color(0.78, 0.1, 0.08)
const BARN_RED := Color(0.72, 0.1, 0.08)
const GLASS := Color(1.0, 0.82, 0.4)
const BLACK := Color(0.1, 0.1, 0.1)
const SKIN := Color(0.97, 0.78, 0.6)
const HAY := Color(0.93, 0.78, 0.3)
## What each kind's ripe crop looks like from across the table.
const CROP_COLOURS := {
	"turnip": Color(0.96, 0.95, 0.9),
	"pumpkin": Color(0.97, 0.5, 0.05),
	"tomato": Color(0.86, 0.1, 0.07),
	"carrot": Color(1.0, 0.56, 0.1),
}
const LEAF := Color(0.14, 0.45, 0.14)
const LEAF_LIGHT := Color(0.3, 0.6, 0.16)
## The crowns through the year, dark to light where there are three; see [method paint_season].
const SPRING_LEAVES: Array[Color] = [
	Color(0.22, 0.52, 0.18), Color(0.36, 0.66, 0.2), Color(0.55, 0.8, 0.28)
]
const SUMMER_LEAVES: Array[Color] = [
	Color(0.08, 0.34, 0.1), Color(0.14, 0.46, 0.13), Color(0.28, 0.58, 0.15)
]
const BLOSSOM := Color(1.0, 0.95, 0.97)
const PINK_BLOSSOM := Color(1.0, 0.72, 0.82)
const GREEN_APPLE := Color(0.55, 0.75, 0.2)
const YELLOWING := Color(0.72, 0.68, 0.2)
const SNOW := Color(0.95, 0.97, 1.0)
const BARE := Color(0.42, 0.3, 0.2)
const WINTER_LEAF := Color(0.1, 0.3, 0.18)
const STRAW := Color(0.74, 0.62, 0.24)
const ICE := Color(0.7, 0.84, 0.95)
## What share of the flowers is out in each season, and in which colours.
const FLOWERING := {"SPRING": 1.0, "SUMMER": 0.35, "AUTUMN": 0.22, "WINTER": 0.0}
const BLOOMS := {
	"SPRING":
	[Color(1, 0.55, 0.7), Color(1, 0.88, 0.25), WHITE, Color(0.72, 0.55, 0.95), Color(1, 0.7, 0.8)],
	"SUMMER": [Color(1, 0.88, 0.25), WHITE, Color(0.9, 0.2, 0.15)],
	"AUTUMN": [Color(0.95, 0.55, 0.1), Color(0.95, 0.8, 0.2), WHITE],
	"WINTER": [WHITE],
}

## Studded bricks, and sloped bricks whose slope faces [code]yaw[/code] (0 = +z).
## The eight raised beds, empty: which crops grow in them is the live farm's business.
const BEDS: Array[Vector2i] = [
	Vector2i(20, 35),
	Vector2i(32, 35),
	Vector2i(8, 35),
	Vector2i(44, 35),
	Vector2i(20, 46),
	Vector2i(32, 46),
	Vector2i(8, 46),
	Vector2i(44, 46)
]

var bricks := {}
## Tree crowns, painted by season: position to [kind, hue, shade, roll]; see [method _lollipop].
var crowns := {}
## Flowers in the grass, painted by season: position to [roll, pick]; see [method _flower_colour].
var flowers := {}
var slopes := {}
## Where the roofs are, which winter covers in snow; see [method _gable].
var roofs := {}
## The sea near the island, which freezes in winter: position to how far out it is, 0 at the beach.
var shore := {}
var glows: Array[Vector3] = []
var rng := RandomNumberGenerator.new()
var environment: Environment
var beam: SpotLight3D
var fill: DirectionalLight3D
var camera: Camera3D
var lamps: Array[OmniLight3D] = []
var material: StandardMaterial3D
var brick_mesh: Mesh
var _crown_order: Array[Vector3i] = []
var _crown_layer: MultiMeshInstance3D
var _flower_order: Array[Vector3i] = []
var _flower_layer: MultiMeshInstance3D
## The grass and sand on top of the island, which snow covers: [index in the ground layer, colour
## as built, whether it is grass, roll].
var _ground: Array[Array] = []
var _ground_layer: MultiMeshInstance3D
## The roofs' bricks open to the sky, in the ground layer and the slope layer: [index, colour as
## built, roll].
var _roof_bricks: Array[Array] = []
var _roof_wedges: Array[Array] = []
## The sea near the island, in the ground layer: [index, colour as built, how far out,
## roll].
var _shore: Array[Array] = []
var _slope_layer: MultiMeshInstance3D
## A farmer as HD-2D pixel art built of tiny bricks: the sprite is read down to a small grid and
## every opaque pixel becomes a brick, two deep. Built once per look and shared by every farmer
## wearing it; the live farm stands each one up facing the camera.
var _looks := {}


func _ready() -> void:
	rng.seed = 21
	_room()
	_island()
	_farmhouse(12, 12)
	_barn(66, 8)
	_well(44, 24)
	_beds()
	_paddock(60, 34)
	_trees()
	_details()
	_emit()
	_smoke(Vector3(9.5 * B, 24 * H, 17.5 * B))
	_dust()
	var live: Node3D = preload("res://live.gd").new()
	live.world = self
	add_child(live)


func put(x: int, y: int, z: int, c: Color) -> void:
	bricks[Vector3i(x, y, z)] = c
	slopes.erase(Vector3i(x, y, z))


func slope(x: int, y: int, z: int, c: Color, yaw: float) -> void:
	slopes[Vector3i(x, y, z)] = [c, yaw]
	bricks.erase(Vector3i(x, y, z))


func box(x0: int, y0: int, z0: int, sx: int, sy: int, sz: int, c: Color) -> void:
	for x in sx:
		for y in sy:
			for z in sz:
				put(x0 + x, y0 + y, z0 + z, c)


func top(x: int, z: int) -> int:
	for y in range(GROUND + 1, -3, -1):
		if bricks.has(Vector3i(x, y, z)):
			return y
	return -1


func _room() -> void:
	var e := Environment.new()
	e.background_mode = Environment.BG_COLOR
	e.background_color = Color(0.1, 0.08, 0.06)
	e.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	e.ambient_light_color = Color(0.55, 0.6, 0.75)
	e.ambient_light_energy = 0.3
	e.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	e.tonemap_exposure = 1.15
	e.ssao_enabled = true
	e.ssao_radius = 0.45
	e.ssao_intensity = 2.4
	e.glow_enabled = true
	e.glow_intensity = 0.6
	e.glow_bloom = 0.12
	e.glow_hdr_threshold = 0.85
	e.volumetric_fog_enabled = true
	e.volumetric_fog_density = 0.01
	e.volumetric_fog_albedo = Color(1.0, 0.92, 0.78)
	e.volumetric_fog_anisotropy = 0.7
	e.volumetric_fog_length = 50
	e.adjustment_enabled = true
	e.adjustment_saturation = 1.15
	e.adjustment_contrast = 1.08
	environment = e
	var we := WorldEnvironment.new()
	we.environment = e
	add_child(we)
	var centre := Vector3(W * B / 2, 0, D * B / 2)
	# the window: one warm beam from the upper left, the room's only real light
	beam = SpotLight3D.new()
	beam.position = centre + Vector3(-16, 18, -10)
	beam.look_at_from_position(beam.position, centre + Vector3(2, 0, 2))
	beam.light_color = Color(1.0, 0.84, 0.6)
	beam.light_energy = 7.0
	beam.spot_range = 60
	beam.spot_angle = 30
	beam.spot_angle_attenuation = 0.6
	beam.light_volumetric_fog_energy = 1.0
	beam.shadow_enabled = true
	beam.shadow_blur = 1.5
	add_child(beam)
	fill = DirectionalLight3D.new()
	fill.rotation_degrees = Vector3(-50, 40, 0)
	fill.light_energy = 0.45
	fill.light_color = Color(0.7, 0.78, 1.0)
	add_child(fill)
	# the table the baseplate stands on, and the workshop wall far behind, both out of focus
	var table := MeshInstance3D.new()
	var tb := BoxMesh.new()
	tb.size = Vector3(44, 1.0, 34)
	table.mesh = tb
	table.position = centre + Vector3(0, -0.72, 0)
	var wood := StandardMaterial3D.new()
	wood.albedo_color = Color(0.36, 0.2, 0.1)
	wood.roughness = 0.5
	table.material_override = wood
	add_child(table)
	var wall := MeshInstance3D.new()
	var wm := BoxMesh.new()
	wm.size = Vector3(90, 30, 1)
	wall.mesh = wm
	wall.position = centre + Vector3(0, 6, -24)
	var dim := StandardMaterial3D.new()
	dim.albedo_color = Color(0.3, 0.25, 0.2)
	wall.material_override = dim
	add_child(wall)
	var bench := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3(30, 1, 4)
	bench.mesh = bm
	bench.position = centre + Vector3(14, 3, -21)
	bench.material_override = wood
	add_child(bench)
	var cam := Camera3D.new()
	camera = cam
	cam.fov = 30
	var yaw := deg_to_rad(CAMERA_YAW)
	var pitch := deg_to_rad(36.0)
	var back := Vector3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch)) * 31
	cam.position = centre + back
	cam.look_at_from_position(cam.position, centre + Vector3(0, 0.3, 0.6))
	var a := CameraAttributesPractical.new()
	a.dof_blur_far_enabled = true
	a.dof_blur_far_distance = 35
	a.dof_blur_far_transition = 10
	a.dof_blur_near_enabled = true
	a.dof_blur_near_distance = 25
	a.dof_blur_near_transition = 5
	a.dof_blur_amount = 0.14
	cam.attributes = a
	add_child(cam)


func _coast(x: int, z: int) -> float:
	var dx := (x - W / 2.0) / (W / 2.0 - 8)
	var dz := (z - D / 2.0) / (D / 2.0 - 6)
	var n := (
		sin(x * 0.23) * 0.05 + cos(z * 0.29 + x * 0.07) * 0.06 + sin(x * 0.09 + z * 0.13) * 0.05
	)
	return pow(abs(dx), 3.2) + pow(abs(dz), 3.2) - 1.0 + n


func _island() -> void:
	for x in range(-16, W + 16):
		for z in range(-14, D + 14):
			var edge := _coast(x, z)
			if edge >= 0:
				var c := WATER
				if edge < 0.07 and rng.randf() < 0.75:
					c = FOAM
				elif edge < 0.16 and rng.randf() < 0.35:
					c = FOAM_BLUE
				put(x, 0, z, c)
				if edge < 0.9:
					shore[Vector3i(x, 0, z)] = edge
				continue
			var height := GROUND
			if edge > -0.1:
				height = 1
			elif edge > -0.2:
				height = 2
			for y in range(0, height):
				put(x, y, z, EARTH)
			var patch := sin(x * 0.17) + cos(z * 0.21 + x * 0.05) + rng.randf() * 0.4
			var c := GRASS_LIGHT if patch > 0.9 else GRASS
			if height < GROUND:
				c = SAND if rng.randf() > 0.2 else SAND_DARK
			put(x, height, z, c)
	# paths: the lane across, the path to the farmhouse door and the barn
	for x in range(6, W - 6):
		for z in range(29, 33):
			if top(x, z) == GROUND:
				put(x, GROUND, z, PATH)
	for z in range(24, 29):
		for x in range(20, 23):
			put(x, GROUND, z, PATH)
	for z in range(20, 29):
		for x in range(71, 75):
			put(x, GROUND, z, PATH)


## A gable roof of sloped bricks over [x0, x0 + sx) x [z0, z0 + sz), its ridge along x. Its
## bricks are marked as roof, for the snow.
func _gable(x0: int, y0: int, z0: int, sx: int, sz: int, c: Color) -> int:
	var layer := 0
	while sz - layer * 2 > 0:
		var zf := z0 + layer
		var zb := z0 + sz - 1 - layer
		for x in range(x0, x0 + sx):
			for z in range(zf, zb + 1):
				roofs[Vector3i(x, y0 + layer, z)] = true
			if zf == zb:
				put(x, y0 + layer, zf, c)
			else:
				slope(x, y0 + layer, zf, c, PI)
				slope(x, y0 + layer, zb, c, 0.0)
				for z in range(zf + 1, zb):
					put(x, y0 + layer, z, c)
		layer += 1
	return y0 + layer


## The farmhouse: log walls on a stone footing, lit windows, a porch, a sloped red roof, a chimney.
func _farmhouse(x: int, z: int) -> void:
	var y := GROUND + 1
	box(x, y, z, 20, 1, 12, STONE_DARK)
	for k in range(1, 9):
		box(x, y + k, z, 20, 1, 12, LOG if k % 2 else WOOD)
	for wx in [x + 2, x + 6, x + 13, x + 17]:
		box(wx, y + 3, z + 12, 2, 3, 1, GLASS)
		put(wx, y + 2, z + 12, WOOD_DARK)
		put(wx + 1, y + 2, z + 12, WOOD_DARK)
	box(x + 9, y + 1, z + 12, 2, 5, 1, WOOD_DARK)
	box(x + 6, y, z + 12, 9, 1, 4, WOOD)
	for px in [x + 6, x + 14]:
		box(px, y + 1, z + 15, 1, 5, 1, WOOD_DARK)
	box(x + 6, y + 6, z + 12, 9, 1, 4, ROOF)
	for px in range(x + 6, x + 15):
		for pz in range(z + 12, z + 16):
			roofs[Vector3i(px, y + 6, pz)] = true
	for gx in [x + 3, x + 15]:
		box(gx, y + 10, z + 5, 2, 2, 1, GLASS)
	_gable(x - 1, y + 9, z - 1, 22, 14, ROOF)
	# the stone chimney stands outside the west gable, as in the owner's farmhouse
	for yy in range(y, y + 19):
		var w := 4 if yy < y + 8 else 3
		for cx in range(x - w, x):
			for cz in range(z + 4, z + 8):
				put(cx, yy, cz, STONE if (cx + yy + cz) % 3 else STONE_DARK)
	box(x - 3, y + 19, z + 4, 3, 1, 4, STONE_DARK)
	# a dormer with a lit window on the front slope
	box(x + 8, y + 11, z + 4, 5, 3, 3, LOG)
	box(x + 9, y + 12, z + 7, 3, 2, 1, GLASS)
	_gable(x + 7, y + 14, z + 3, 7, 5, ROOF)
	# a barrel on the porch
	box(x + 12, y + 1, z + 13, 2, 3, 2, WOOD)
	box(x + 12, y + 2, z + 13, 2, 1, 2, STONE_DARK)
	glows.append(Vector3(x + 5.5, y + 5, z + 15.5))
	glows.append(Vector3(x + 15.5, y + 5, z + 15.5))
	# the shipping bin by the door: a dark lidded box, the farm's ledger
	box(x + 16, y, z + 14, 4, 2, 3, STONE_DARK)
	box(x + 16, y + 2, z + 14, 4, 1, 3, Color(0.22, 0.23, 0.25))


## The barn: red planks, white trim and X doors, a curved white roof; its silo beside it.
func _barn(x: int, z: int) -> void:
	var y := GROUND + 1
	box(x, y, z, 16, 10, 12, BARN_RED)
	for k in [0, 16 - 1]:
		box(x + k, y, z + 12, 1, 10, 1, WHITE)
	box(x, y + 9, z + 12, 16, 1, 1, WHITE)
	box(x + 5, y, z + 12, 6, 7, 1, BARN_RED.darkened(0.2))
	box(x + 5, y + 7, z + 12, 6, 1, 1, WHITE)
	for k in [5, 10]:
		box(x + k, y, z + 12, 1, 7, 1, WHITE)
	for d in 6:
		put(x + 5 + d, y + d, z + 13, WHITE)
		put(x + 10 - d, y + d, z + 13, WHITE)
	box(x + 6, y + 11, z + 11, 4, 3, 1, WHITE)
	box(x + 7, y + 11, z + 12, 2, 2, 1, HAY)
	# the curved roof: two steep courses, then a gentle slope to the ridge
	var ry := y + 10
	for k in 2:
		box(x - 1, ry + k, z - 1 + k, 18, 1, 14 - k * 2, WHITE)
		for xx in range(x - 1, x + 17):
			slope(xx, ry + k, z - 1 + k, WHITE, PI)
			slope(xx, ry + k, z + 12 - k, WHITE, 0.0)
	_gable(x - 1, ry + 2, z + 1, 18, 10, Color(0.88, 0.88, 0.86))
	# the silo: a tall ring of pale bricks with a domed cap
	for yy in range(y, y + 22):
		for sx in range(x + 17, x + 24):
			for sz in range(z + 2, z + 9):
				var d := Vector2(sx - (x + 20), sz - (z + 5)).length()
				if d <= 3.2 and (d > 2.2 or yy == y + 21):
					put(sx, yy, sz, Color(0.82, 0.83, 0.84) if yy % 3 else Color(0.72, 0.73, 0.75))
	for sx in range(x + 18, x + 23):
		for sz in range(z + 3, z + 8):
			if Vector2(sx - (x + 20), sz - (z + 5)).length() <= 2.2:
				put(sx, y + 22, sz, Color(0.7, 0.71, 0.73))
	box(x + 20, y + 23, z + 5, 1, 3, 1, BLACK)
	box(x + 19, y + 25, z + 5, 3, 1, 1, BLACK)


func _well(x: int, z: int) -> void:
	var y := GROUND + 1
	for dx in range(-2, 3):
		for dz in range(-2, 3):
			var d := Vector2(dx, dz).length()
			if d <= 2.4 and d > 1.1:
				box(x + dx, y, z + dz, 1, 3, 1, STONE if (dx + dz) % 2 else STONE_DARK)
	box(x - 1, y, z - 1, 3, 2, 3, WATER)
	box(x - 3, y + 3, z, 1, 5, 1, WOOD_DARK)
	box(x + 3, y + 3, z, 1, 5, 1, WOOD_DARK)
	_gable(x - 3, y + 8, z - 2, 7, 5, WOOD)
	box(x, y + 4, z, 1, 2, 1, WOOD)


func _beds() -> void:
	for bed in BEDS:
		for x in range(bed.x, bed.x + 11):
			for z in range(bed.y, bed.y + 9):
				var rim := x == bed.x or x == bed.x + 10 or z == bed.y or z == bed.y + 8
				put(x, GROUND + 1, z, WOOD if rim else SOIL)


## One crop in its 3 x 2 cell, readable from across the table at every stage: a bright sprout, a
## leafy plant, the plant with its fruit coming, then the fruit ripe and big in the kind's colour.
func crop(kind: String, stage: int, x: int, z: int) -> void:
	var y := GROUND + 2
	var ripe: Color = CROP_COLOURS.get(kind, RED)
	match stage:
		0:
			put(x + 1, y, z, LEAF_LIGHT)
			put(x + 1, y + 1, z, LEAF_LIGHT)
			put(x, y + 1, z + 1, LEAF_LIGHT)
			put(x + 2, y + 1, z + 1, LEAF_LIGHT)
		1:
			box(x, y, z, 3, 1, 2, LEAF_LIGHT)
			box(x + 1, y + 1, z, 1, 2, 2, LEAF)
		2:
			box(x, y, z, 3, 2, 2, LEAF)
			box(x, y + 2, z, 3, 1, 1, LEAF_LIGHT)
			box(x + 1, y + 1, z + 1, 1, 1, 1, ripe.lerp(LEAF_LIGHT, 0.55))
		_:
			box(x, y, z, 3, 1, 2, LEAF)
			box(x, y + 1, z, 3, 2, 2, ripe)
			box(x + 1, y + 3, z, 1, 1, 1, LEAF_LIGHT)
			if kind == "turnip":
				box(x, y + 2, z, 3, 1, 2, Color(0.66, 0.34, 0.66))


func _fence(x0: int, z0: int, x1: int, z1: int) -> void:
	for x in range(x0, x1 + 1):
		for z in range(z0, z1 + 1):
			if x != x0 and x != x1 and z != z0 and z != z1:
				continue
			var y := GROUND + 1
			if (x + z) % 3 == 0:
				box(x, y, z, 1, 3, 1, WOOD_DARK)
			else:
				put(x, y + 1, z, WOOD)


func _paddock(x: int, z: int) -> void:
	_fence(x, z, x + 24, z + 16)
	box(x + 16, GROUND + 1, z + 2, 6, 2, 3, STONE)
	box(x + 17, GROUND + 2, z + 3, 4, 1, 1, WATER)
	_cow(x + 3, z + 4)
	_cow(x + 9, z + 9)
	_cow(x + 15, z + 11)
	for h in [Vector2i(x + 5, z + 13), Vector2i(x + 12, z + 5), Vector2i(x + 20, z + 8)]:
		_hen(h.x, h.y)


## A round tree: a trunk and a crown. The crown's bricks are not coloured here but remembered:
## which kind of tree, how high in the crown, and a roll of the dice for blossom, fruit and
## yellowing, so that [method paint_season] can colour the whole wood for the month without
## rebuilding it. [param kind] is "apple", "evergreen" (green all year) or "turning" (it turns
## [param hue] in autumn and goes bare in winter).
func _lollipop(x: int, z: int, r: int, kind: String, hue := LEAF) -> void:
	var y := top(x, z) + 1
	box(x, y, z, 1, r + 2, 1, TRUNK)
	var cy := y + r + 2 + r - 1
	for dx in range(-r, r + 1):
		for dy in range(-r, r + 1):
			for dz in range(-r, r + 1):
				if Vector3(dx, dy * 1.25, dz).length() > r + 0.35:
					continue
				var shade := 1
				if dy >= r - 1:
					shade = 2
				elif dy <= -r + 1:
					shade = 0
				crowns[Vector3i(x + dx, cy + dy, z + dz)] = [kind, hue, shade, rng.randf()]


func _trees() -> void:
	var autumn := [
		Color(0.92, 0.45, 0.06),
		Color(0.95, 0.72, 0.1),
		Color(0.55, 0.62, 0.12),
		Color(0.8, 0.3, 0.06)
	]
	_lollipop(52, 22, 3, "apple")
	_lollipop(88, 26, 3, "apple")
	for spot in [
		Vector2i(8, 6),
		Vector2i(14, 4),
		Vector2i(36, 6),
		Vector2i(44, 10),
		Vector2i(54, 6),
		Vector2i(4, 24),
		Vector2i(6, 14),
		Vector2i(90, 12),
		Vector2i(90, 40),
		Vector2i(4, 34),
		Vector2i(58, 54),
		Vector2i(88, 52)
	]:
		if top(spot.x, spot.y) == GROUND:
			var hue: Color = autumn[rng.randi() % autumn.size()]
			_lollipop(spot.x, spot.y, rng.randi_range(2, 3), "turning", hue)
	for i in 400:
		var x := rng.randi_range(0, W)
		var z := rng.randi_range(0, D)
		var busy := (z > 18 and z < 58 and x > 4 and x < 88) or (x > 8 and x < 36 and z > 8)
		busy = busy or (x > 60 and x < 92 and z < 22)
		if _coast(x, z) < -0.22 and not busy and _room_for_tree(x, z):
			if rng.randf() < 0.5:
				_lollipop(x, z, 2, "turning", autumn[rng.randi() % autumn.size()])
			else:
				_lollipop(x, z, 2, "evergreen", LEAF if rng.randf() < 0.6 else LEAF_LIGHT)


func _room_for_tree(x: int, z: int) -> bool:
	for dx in range(-3, 4):
		for dz in range(-3, 4):
			if top(x + dx, z + dz) != GROUND or bricks.has(Vector3i(x + dx, GROUND + 1, z + dz)):
				return false
	return true


func _details() -> void:
	# a pier into the water at the front left, rocks, flowers, lanterns and a hay bale
	for x in range(10, 16):
		for z in range(D - 7, D + 4):
			put(x, 1, z, WOOD if (z % 2) else WOOD_DARK)
	for post in [Vector2i(10, D + 3), Vector2i(15, D + 3)]:
		box(post.x, 0, post.y, 1, 3, 1, WOOD_DARK)
	for rk in [
		Vector2i(2, 44), Vector2i(34, 60), Vector2i(80, 58), Vector2i(94, 20), Vector2i(62, -4)
	]:
		var ry := maxi(top(rk.x, rk.y), 0) + 1
		box(rk.x, ry, rk.y, 3, 1, 2, STONE)
		box(rk.x + 1, ry + 1, rk.y, 2, 1, 1, STONE_DARK)
	for i in 600:
		var x := rng.randi_range(0, W)
		var z := rng.randi_range(0, D)
		if top(x, z) == GROUND and bricks.get(Vector3i(x, GROUND, z)) in [GRASS, GRASS_LIGHT]:
			flowers[Vector3i(x, GROUND + 1, z)] = [rng.randf(), rng.randf()]
	for lamp in [Vector2i(24, 28), Vector2i(40, 28), Vector2i(70, 28)]:
		box(lamp.x, GROUND + 1, lamp.y, 1, 4, 1, WOOD_DARK)
		put(lamp.x, GROUND + 5, lamp.y, GLASS)
		glows.append(Vector3(lamp.x + 0.5, GROUND + 5.5, lamp.y + 0.5))
	box(84, GROUND + 1, 22, 3, 2, 2, HAY)


func _cow(x: int, z: int) -> void:
	var y := GROUND + 1
	for lx in [0, 4]:
		for lz in [0, 2]:
			put(x + lx, y, z + lz, WHITE)
	box(x, y + 1, z, 5, 2, 3, WHITE)
	box(x + 1, y + 2, z, 2, 1, 3, BLACK)
	box(x + 3, y + 1, z + 2, 1, 1, 1, BLACK)
	box(x + 5, y + 2, z, 2, 2, 3, WHITE)
	box(x + 6, y + 2, z, 1, 1, 3, Color(0.96, 0.68, 0.66))
	put(x + 5, y + 4, z, BLACK)
	put(x + 5, y + 4, z + 2, BLACK)


func _hen(x: int, z: int) -> void:
	var y := GROUND + 1
	box(x, y, z, 2, 1, 2, WHITE)
	put(x + 1, y + 1, z, WHITE)
	put(x + 1, y + 2, z, RED)
	put(x + 2, y + 1, z, Color(1, 0.7, 0.1))


func farmer_look(look: int) -> MultiMesh:
	if _looks.has(look):
		return _looks[look]
	var name := "villager_a" if look == 0 else "villager_b"
	# Read as bytes through Godot's own file access: in an exported farm the picture lives inside
	# the packed project, where no path on disk reaches it.
	var img := Image.new()
	img.load_png_from_buffer(FileAccess.get_file_as_bytes("res://art/%s.png" % name))
	var rows := 30
	var cols := int(round(img.get_width() * rows / float(img.get_height())))
	img.resize(cols, rows, Image.INTERPOLATE_LANCZOS)
	var px := FARMER_HEIGHT / rows
	var items := []
	for y in rows:
		for x in cols:
			var c := img.get_pixel(x, y)
			if c.a < 0.5:
				continue
			c.a = 1.0
			for d in 2:
				var local := Vector3((x - cols / 2.0) * px, (rows - 1 - y) * px, -d * px)
				items.append([local, c.darkened(0.25 * d)])
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.use_colors = true
	mm.mesh = _mini_brick(px)
	mm.instance_count = items.size()
	for i in items.size():
		mm.set_instance_transform(i, Transform3D(Basis(), items[i][0]))
		mm.set_instance_color(i, items[i][1])
	_looks[look] = mm
	return mm


func _mini_brick(px: float) -> Mesh:
	var st := SurfaceTool.new()
	var cube := BoxMesh.new()
	cube.size = Vector3(px * 0.96, px * 0.96, px * 0.96)
	st.append_from(cube, 0, Transform3D(Basis(), Vector3(0, px / 2, 0)))
	var stud := CylinderMesh.new()
	stud.top_radius = px * 0.28
	stud.bottom_radius = px * 0.28
	stud.height = px * 0.3
	stud.radial_segments = 8
	stud.rings = 1
	st.append_from(stud, 0, Transform3D(Basis(Vector3.RIGHT, PI / 2), Vector3(0, px / 2, px * 0.6)))
	return st.commit()


## Dresses the island for [param season], one of the reducer's WINTER, SPRING, SUMMER or AUTUMN.
## The trees: blossom in spring, deep green in summer, turning in autumn, snow and bare wood in
## winter. The ground: fresh in spring, gold-flecked in autumn, under snow in winter, as are the
## roofs, with ice on the sea along the shore. The flowers: a meadow in spring, fewer through
## summer and autumn, none in winter. Only instances already built are recoloured, or hidden, so
## nothing is rebuilt.
func paint_season(season: String) -> void:
	var leaves := _crown_layer.multimesh
	for i in _crown_order.size():
		var leaf: Array = crowns[_crown_order[i]]
		leaves.set_instance_color(i, _leaf_colour(season, leaf[0], leaf[1], leaf[2], leaf[3]))
	var ground := _ground_layer.multimesh
	for g in _ground:
		ground.set_instance_color(g[0], _ground_colour(season, g[1], g[2], g[3]))
	for r in _roof_bricks:
		ground.set_instance_color(r[0], _roof_colour(season, r[1], r[2]))
	for w in _shore:
		ground.set_instance_color(w[0], _sea_colour(season, w[1], w[2], w[3]))
	var wedges := _slope_layer.multimesh
	for r in _roof_wedges:
		wedges.set_instance_color(r[0], _roof_colour(season, r[1], r[2]))
	var blooms := _flower_layer.multimesh
	for i in _flower_order.size():
		var p := _flower_order[i]
		var flower: Array = flowers[p]
		var out: bool = flower[0] < FLOWERING[season]
		var palette: Array = BLOOMS[season]
		var size := 1.0 if out else 0.0
		var at := Vector3(p.x * B, p.y * H, p.z * B)
		blooms.set_instance_transform(i, Transform3D(Basis().scaled(Vector3.ONE * size), at))
		blooms.set_instance_color(i, palette[int(flower[1] * palette.size())])


## A roof brick with nothing on top of it, which is where snow settles.
func _open_roof(p: Vector3i) -> bool:
	var above := p + Vector3i.UP
	return roofs.has(p) and not bricks.has(above) and not slopes.has(above)


## A fixed roll of the dice for a brick, from where it is, so it draws nothing from [member rng]
## and adding one leaves the rest of the island as it was.
func _roll(p: Vector3i) -> float:
	return posmod(hash(p), 1000) / 1000.0


## A roof in [param season]: under snow in winter, bar a few bricks the wind has cleared.
func _roof_colour(season: String, built: Color, roll: float) -> Color:
	if season != "WINTER" or roll < 0.1:
		return built
	return SNOW.darkened(roll * 0.06)


## The sea in [param season]: in winter it freezes along the beach, and breaks into floes further
## out, where [param out] runs from 0 at the sand to 0.9, past which the sea stays open.
func _sea_colour(season: String, built: Color, out: float, roll: float) -> Color:
	if season != "WINTER" or roll > 1.4 - out * 1.6:
		return built
	return ICE.lightened(roll * 0.35)


## The top of the island in [param season]. Snow leaves a little grass showing, and more of the
## sand, which the sea keeps clearing.
func _ground_colour(season: String, built: Color, grass: bool, roll: float) -> Color:
	var colour := built
	match season:
		"WINTER":
			var bare := 0.08 if grass else 0.45
			colour = built.darkened(0.3) if roll < bare else SNOW.darkened(roll * 0.07)
		"SPRING":
			colour = built.lightened(0.1) if grass else built
		"AUTUMN":
			colour = built.lerp(STRAW, 0.15 + 0.3 * roll) if grass else built
	return colour


func _leaf_colour(season: String, kind: String, hue: Color, shade: int, roll: float) -> Color:
	match season:
		"SPRING":
			return _spring_leaf(kind, shade, roll)
		"SUMMER":
			return _summer_leaf(kind, shade, roll)
		"WINTER":
			return _winter_leaf(kind, shade, roll)
	return _autumn_leaf(kind, hue, shade, roll)


func _spring_leaf(kind: String, shade: int, roll: float) -> Color:
	if kind == "apple" and roll < 0.25:
		return BLOSSOM
	if kind == "turning" and roll < 0.35:
		return PINK_BLOSSOM
	return SPRING_LEAVES[shade]


func _summer_leaf(kind: String, shade: int, roll: float) -> Color:
	if kind == "apple" and roll < 0.12:
		return GREEN_APPLE
	return SUMMER_LEAVES[shade]


func _winter_leaf(kind: String, shade: int, roll: float) -> Color:
	if shade == 2 or roll < 0.15:
		return SNOW
	var bough := BARE if kind == "turning" else WINTER_LEAF
	return bough.darkened(0.15 * (1 - shade))


func _autumn_leaf(kind: String, hue: Color, shade: int, roll: float) -> Color:
	if kind == "apple" and roll < 0.12:
		return RED
	if kind == "evergreen" and roll < 0.25:
		return YELLOWING
	return [hue.darkened(0.2), hue, hue.lightened(0.18)][shade]


func _brick_mesh() -> Mesh:
	var st := SurfaceTool.new()
	var cube := BoxMesh.new()
	cube.size = Vector3(B * 0.97, H * 0.98, B * 0.97)
	st.append_from(cube, 0, Transform3D(Basis(), Vector3(0, H / 2, 0)))
	var stud := CylinderMesh.new()
	stud.top_radius = B * 0.3
	stud.bottom_radius = B * 0.3
	stud.height = H * 0.28
	stud.radial_segments = 12
	stud.rings = 1
	st.append_from(stud, 0, Transform3D(Basis(), Vector3(0, H * 1.12, 0)))
	return st.commit()


func _slope_mesh() -> Mesh:
	# a wedge rising toward -z: flat at +z, full height at -z
	var p := PrismMesh.new()
	p.left_to_right = 0.0
	p.size = Vector3(B * 0.97, H * 0.98, B * 0.97)
	var st := SurfaceTool.new()
	st.append_from(p, 0, Transform3D(Basis(Vector3.UP, -PI / 2), Vector3(0, H / 2, 0)))
	return st.commit()


func instances(mesh: Mesh, items: Array, mat: Material) -> MultiMeshInstance3D:
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.use_colors = true
	mm.mesh = mesh
	mm.instance_count = items.size()
	for i in items.size():
		mm.set_instance_transform(i, items[i][0])
		mm.set_instance_color(i, items[i][1])
	var mi := MultiMeshInstance3D.new()
	mi.multimesh = mm
	mi.material_override = mat
	add_child(mi)
	return mi


func _emit() -> void:
	var mat := StandardMaterial3D.new()
	mat.vertex_color_use_as_albedo = true
	mat.roughness = 0.3
	mat.metallic_specular = 0.65
	material = mat
	brick_mesh = _brick_mesh()
	var around := [
		Vector3i(0, 1, 0),
		Vector3i(1, 0, 0),
		Vector3i(-1, 0, 0),
		Vector3i(0, 0, 1),
		Vector3i(0, 0, -1)
	]
	var shown := []
	for p in bricks:
		for n in around:
			if not bricks.has(p + n):
				var c: Color = bricks[p]
				var jittered := c * rng.randf_range(0.96, 1.04)
				if p.y <= GROUND and c in [GRASS, GRASS_LIGHT, SAND, SAND_DARK]:
					_ground.append([shown.size(), jittered, c in [GRASS, GRASS_LIGHT], rng.randf()])
				if _open_roof(p):
					_roof_bricks.append([shown.size(), jittered, _roll(p)])
				if shore.has(p):
					_shore.append([shown.size(), jittered, shore[p], _roll(p)])
				shown.append([Transform3D(Basis(), Vector3(p.x * B, p.y * H, p.z * B)), jittered])
				break
	_ground_layer = instances(brick_mesh, shown, mat)
	var leaves := []
	for p in crowns:
		for n in around:
			if not crowns.has(p + n) and not bricks.has(p + n):
				_crown_order.append(p)
				leaves.append([Transform3D(Basis(), Vector3(p.x * B, p.y * H, p.z * B)), LEAF])
				break
	_crown_layer = instances(brick_mesh, leaves, mat)
	var blooms := []
	for p in flowers:
		_flower_order.append(p)
		blooms.append([Transform3D(Basis(), Vector3(p.x * B, p.y * H, p.z * B)), WHITE])
	_flower_layer = instances(brick_mesh, blooms, mat)
	var wedges := []
	for p in slopes:
		var s: Array = slopes[p]
		var t := Transform3D(Basis(Vector3.UP, s[1]), Vector3(p.x * B, p.y * H, p.z * B))
		if _open_roof(p):
			_roof_wedges.append([wedges.size(), s[0], _roll(p)])
		wedges.append([t, s[0]])
	_slope_layer = instances(_slope_mesh(), wedges, mat)
	paint_season("AUTUMN")
	var lit := StandardMaterial3D.new()
	lit.albedo_color = GLASS
	lit.emission_enabled = true
	lit.emission = Color(1.0, 0.75, 0.35)
	lit.emission_energy_multiplier = 3.0
	for g in glows:
		var o := OmniLight3D.new()
		o.position = Vector3(g.x * B, g.y * H, g.z * B)
		o.light_color = Color(1.0, 0.75, 0.4)
		o.light_energy = 1.2
		o.omni_range = 3.2
		add_child(o)
		lamps.append(o)
	printerr("bricks shown: ", shown.size(), " slopes: ", wedges.size())


func _dust() -> void:
	var p := CPUParticles3D.new()
	p.amount = 70
	p.lifetime = 12.0
	p.preprocess = 12.0
	p.emission_shape = CPUParticles3D.EMISSION_SHAPE_BOX
	p.emission_box_extents = Vector3(5, 4, 4)
	p.position = Vector3(W * B / 2 - 7, 7, D * B / 2 - 5)
	p.gravity = Vector3(0, 0.02, 0)
	p.initial_velocity_min = 0.05
	p.initial_velocity_max = 0.2
	p.spread = 60
	var q := QuadMesh.new()
	q.size = Vector2(0.05, 0.05)
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.billboard_mode = BaseMaterial3D.BILLBOARD_ENABLED
	m.albedo_color = Color(1.0, 0.93, 0.7)
	m.emission_enabled = true
	m.emission = Color(1.0, 0.9, 0.6)
	m.emission_energy_multiplier = 3.0
	q.material = m
	p.mesh = q
	add_child(p)


func _smoke(at: Vector3) -> void:
	# puffs of translucent bricks, as the chimney in a brick set would have
	var p := CPUParticles3D.new()
	p.amount = 10
	p.lifetime = 4.0
	p.preprocess = 4.0
	p.position = at
	p.direction = Vector3(0.2, 1, 0)
	p.spread = 8
	p.gravity = Vector3(0.1, 0.15, 0)
	p.initial_velocity_min = 0.3
	p.initial_velocity_max = 0.4
	var fade := Gradient.new()
	fade.set_color(0, Color(0.95, 0.96, 0.98, 0.85))
	fade.set_color(1, Color(0.95, 0.96, 0.98, 0.0))
	p.color_ramp = fade
	var q := BoxMesh.new()
	q.size = Vector3(0.25, 0.2, 0.25)
	var m := StandardMaterial3D.new()
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.vertex_color_use_as_albedo = true
	m.roughness = 0.2
	q.material = m
	p.mesh = q
	add_child(p)
