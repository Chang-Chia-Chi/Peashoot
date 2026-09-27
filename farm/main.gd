extends Node3D

## One brick: a quarter of a tile across, a fifth of a tile tall, a stud on top.
const B := 0.25
const H := 0.2
const W := 104
const D := 64
const GROUND := 3

const GRASS := Color(0.33, 0.62, 0.14)
const GRASS_LIME := Color(0.55, 0.76, 0.12)
const GRASS_DARK := Color(0.2, 0.46, 0.13)
const SAND := Color(0.9, 0.78, 0.52)
const EARTH := Color(0.66, 0.45, 0.26)
const SOIL := Color(0.4, 0.25, 0.14)
const SOIL_RIDGE := Color(0.5, 0.33, 0.19)
const LANE := Color(0.87, 0.72, 0.47)
const WATER := Color(0.04, 0.3, 0.75)
const WATER_LIGHT := Color(0.18, 0.5, 0.92)
const FOAM := Color(0.93, 0.97, 1.0)
const TRUNK := Color(0.42, 0.26, 0.14)
const LEAF := Color(0.13, 0.46, 0.16)
const LEAF_MID := Color(0.22, 0.58, 0.18)
const LEAF_TOP := Color(0.45, 0.72, 0.16)
const WOOD := Color(0.64, 0.4, 0.2)
const WOOD_DARK := Color(0.45, 0.27, 0.13)
const ROOF := Color(0.7, 0.18, 0.12)
const ROOF_DARK := Color(0.52, 0.12, 0.09)
const STONE := Color(0.62, 0.62, 0.64)
const STONE_DARK := Color(0.45, 0.46, 0.5)
const WHITE := Color(0.96, 0.96, 0.94)
const RED := Color(0.8, 0.12, 0.1)
const GLASS := Color(1.0, 0.86, 0.45)
const BLACK := Color(0.12, 0.12, 0.12)
const SKIN := Color(0.97, 0.78, 0.6)
const HAY := Color(0.93, 0.78, 0.3)

var bricks := {}
var rng := RandomNumberGenerator.new()


func _ready() -> void:
	rng.seed = 11
	_look()
	_island()
	_lane()
	_farmhouse(10, 9)
	_barn(80, 6)
	_well(47, 21)
	_tree(55, 19, 4, "apple")
	_paddock()
	_fields()
	_forest()
	_flowers()
	_folk()
	_emit()
	_smoke(Vector3(13.5 * B, 17 * H, 12.5 * B))
	_dust()
	if "--shot" in OS.get_cmdline_user_args():
		for i in 40:
			await get_tree().process_frame
		await RenderingServer.frame_post_draw
		get_viewport().get_texture().get_image().save_png("res://shot.png")
		get_tree().quit()


func put(x: int, y: int, z: int, c: Color) -> void:
	bricks[Vector3i(x, y, z)] = c


func box(x0: int, y0: int, z0: int, sx: int, sy: int, sz: int, c: Color) -> void:
	for x in sx:
		for y in sy:
			for z in sz:
				put(x0 + x, y0 + y, z0 + z, c)


func top(x: int, z: int) -> int:
	for y in range(GROUND + 2, -3, -1):
		if bricks.has(Vector3i(x, y, z)):
			return y
	return -1


func _look() -> void:
	var e := Environment.new()
	e.background_mode = Environment.BG_COLOR
	e.background_color = Color(0.36, 0.42, 0.34)
	e.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	e.ambient_light_color = Color(0.62, 0.7, 0.85)
	e.ambient_light_energy = 0.38
	e.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	e.tonemap_exposure = 1.1
	e.ssao_enabled = true
	e.ssao_radius = 0.5
	e.ssao_intensity = 2.2
	e.glow_enabled = true
	e.glow_intensity = 0.55
	e.glow_bloom = 0.1
	e.glow_hdr_threshold = 0.9
	e.glow_blend_mode = Environment.GLOW_BLEND_MODE_SOFTLIGHT
	e.volumetric_fog_enabled = true
	e.volumetric_fog_density = 0.0028
	e.volumetric_fog_albedo = Color(1.0, 0.9, 0.72)
	e.volumetric_fog_anisotropy = 0.65
	e.volumetric_fog_length = 60
	e.adjustment_enabled = true
	e.adjustment_saturation = 1.18
	e.adjustment_contrast = 1.06
	var we := WorldEnvironment.new()
	we.environment = e
	add_child(we)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-36, 140, 0)
	sun.light_color = Color(1.0, 0.86, 0.66)
	sun.light_energy = 1.55
	sun.light_volumetric_fog_energy = 1.8
	sun.shadow_enabled = true
	sun.shadow_blur = 1.2
	sun.directional_shadow_max_distance = 60
	add_child(sun)
	var cam := Camera3D.new()
	cam.fov = 27
	var centre := Vector3(W * B / 2, 0, D * B / 2 + 0.5)
	cam.position = centre + Vector3(0, sin(deg_to_rad(42)), cos(deg_to_rad(42))) * 36
	cam.rotation_degrees = Vector3(-42, 0, 0)
	var a := CameraAttributesPractical.new()
	a.dof_blur_far_enabled = true
	a.dof_blur_far_distance = 38
	a.dof_blur_far_transition = 7
	a.dof_blur_near_enabled = true
	a.dof_blur_near_distance = 31
	a.dof_blur_near_transition = 4
	a.dof_blur_amount = 0.16
	cam.attributes = a
	add_child(cam)


func _coast(x: int, z: int) -> float:
	var dx := (x - W / 2.0) / (W / 2.0 - 5)
	var dz := (z - D / 2.0) / (D / 2.0 - 4)
	var n := (
		sin(x * 0.19) * 0.05 + cos(z * 0.23 + x * 0.05) * 0.06 + sin(x * 0.07 + z * 0.13) * 0.05
	)
	return pow(abs(dx), 5) + pow(abs(dz), 5) - 1.0 + n


func _island() -> void:
	for x in range(-40, W + 40):
		for z in range(-60, D + 22):
			var edge := _coast(x, z)
			if edge >= 0:
				var near := edge < 0.12
				var c := WATER
				if near and rng.randf() < 0.55:
					c = FOAM if edge < 0.05 else WATER_LIGHT
				elif rng.randf() < 0.1:
					c = WATER_LIGHT
				put(x, -1, z, c)
				continue
			var height := GROUND
			if edge > -0.06:
				height = GROUND - 2
			elif edge > -0.14:
				height = GROUND - 1
			for y in range(-1, height):
				put(x, y, z, EARTH if (y + x + z) % 5 else EARTH.darkened(0.12))
			var patch := sin(x * 0.11) + cos(z * 0.15 + x * 0.04) + rng.randf() * 0.5
			var c := GRASS
			if patch > 1.1:
				c = GRASS_LIME
			elif patch < -0.8:
				c = GRASS_DARK
			if height < GROUND:
				c = SAND if height == GROUND - 2 else c
			put(x, height, z, c)


func _lane() -> void:
	for x in range(0, W):
		for z in range(26, 31):
			if top(x, z) == GROUND:
				put(x, GROUND, z, LANE if rng.randf() > 0.08 else LANE.darkened(0.1))
	for z in range(0, 26):
		for x in range(64, 69):
			if top(x, z) == GROUND:
				put(x, GROUND, z, LANE if rng.randf() > 0.08 else LANE.darkened(0.1))
	for z in range(22, 26):
		for x in range(17, 21):
			put(x, GROUND, z, STONE)


## The farmhouse: log walls, a stone footing, lit windows, a porch and a stepped two-tone roof.
func _farmhouse(x: int, z: int) -> void:
	var y := GROUND + 1
	box(x, y, z, 20, 1, 12, STONE_DARK)
	for k in range(1, 7):
		box(x, y + k, z, 20, 1, 12, WOOD if k % 2 else WOOD_DARK)
	for wx in [x + 3, x + 7, x + 12, x + 16]:
		box(wx, y + 3, z + 12, 2, 2, 1, GLASS)
	box(x + 9, y + 1, z + 12, 2, 4, 1, WOOD_DARK)
	put(x + 10, y + 3, z + 13, Color(0.9, 0.75, 0.2))
	box(x + 6, y, z + 12, 8, 1, 3, WOOD)
	for px in [x + 6, x + 13]:
		box(px, y + 1, z + 14, 1, 4, 1, WOOD_DARK)
	box(x + 6, y + 5, z + 12, 8, 1, 3, ROOF_DARK)
	for s in 6:
		box(x - 1, y + 7 + s, z - 1 + s, 22, 1, 14 - s * 2, ROOF if s % 2 == 0 else ROOF_DARK)
	box(x + 3, y + 7, z + 2, 2, 11, 2, STONE)
	# the shipping bin by the door: a lidded wooden crate, the farm's ledger
	box(x + 16, y, z + 14, 4, 2, 3, WOOD)
	box(x + 16, y + 2, z + 14, 4, 1, 3, WOOD_DARK)


## The barn: red planks with white trim, a gambrel roof, big doors; and its silo.
func _barn(x: int, z: int) -> void:
	var y := GROUND + 1
	box(x, y, z, 14, 8, 11, RED)
	for k in [0, 7]:
		box(x, y + k, z + 11, 14, 1, 1, WHITE)
	for k in [0, 13]:
		box(x + k, y, z + 11, 1, 8, 1, WHITE)
	box(x + 4, y, z + 11, 6, 6, 1, WOOD)
	for d in 6:
		put(x + 4 + d, y + d, z + 12, WHITE)
		put(x + 9 - d, y + d, z + 12, WHITE)
	box(x + 6, y + 8, z + 11, 2, 2, 1, HAY)
	for s in 5:
		var inset := s if s < 3 else 2 + (s - 2) * 2
		box(x - 1, y + 8 + s, z - 1 + inset, 16, 1, 13 - inset * 2, STONE_DARK)
	for yy in range(y, y + 18):
		for sx in range(x + 15, x + 21):
			for sz in range(z + 2, z + 8):
				var d := Vector2(sx - (x + 17.5), sz - (z + 4.5)).length()
				if d <= 2.9:
					var shell := d > 1.9 or yy >= y + 16
					if shell:
						put(sx, yy, sz, STONE if (yy / 2) % 2 else Color(0.72, 0.73, 0.76))
	box(x + 16, y + 18, z + 3, 3, 1, 3, STONE_DARK)


func _well(x: int, z: int) -> void:
	var y := GROUND + 1
	for dx in range(-2, 3):
		for dz in range(-2, 3):
			var d := Vector2(dx, dz).length()
			if d <= 2.3 and d > 1.1:
				box(x + dx, y, z + dz, 1, 3, 1, STONE if (dx + dz) % 2 else STONE_DARK)
	box(x - 1, y, z - 1, 3, 2, 3, WATER)
	box(x - 3, y + 3, z, 1, 5, 1, WOOD_DARK)
	box(x + 3, y + 3, z, 1, 5, 1, WOOD_DARK)
	box(x - 3, y + 8, z - 1, 7, 1, 3, ROOF)
	box(x - 2, y + 9, z, 5, 1, 1, ROOF_DARK)
	put(x, y + 6, z, WOOD)


func _tree(x: int, z: int, r: int, kind: String) -> void:
	var y := top(x, z) + 1
	var trunk := r + 1
	box(x, y, z, 1, trunk, 1, TRUNK)
	var palette := [LEAF, LEAF_MID, LEAF_TOP]
	if kind == "autumn":
		palette = [Color(0.72, 0.28, 0.08), Color(0.9, 0.45, 0.1), Color(0.98, 0.72, 0.18)]
	var cy := y + trunk + r - 1
	for dx in range(-r, r + 1):
		for dy in range(-r + 1, r + 1):
			for dz in range(-r, r + 1):
				var d := Vector3(dx, dy * 1.2, dz).length()
				if d > r + 0.4 or rng.randf() < 0.06:
					continue
				var shade: int = 0 if dy < 0 else (2 if dy >= r - 1 and rng.randf() < 0.7 else 1)
				var c: Color = palette[shade]
				if kind == "apple" and rng.randf() < 0.09 and dy > -r + 1:
					c = RED
				put(x + dx, cy + dy, z + dz, c)


func _pine(x: int, z: int) -> void:
	var y := top(x, z) + 1
	box(x, y, z, 1, 2, 1, TRUNK)
	for s in 4:
		var r := 3 - s
		for dx in range(-r, r + 1):
			for dz in range(-r, r + 1):
				if abs(dx) + abs(dz) <= r + 1:
					put(x + dx, y + 2 + s * 2, z + dz, Color(0.07, 0.34, 0.2))
					put(x + dx, y + 3 + s * 2, z + dz, Color(0.1, 0.42, 0.22))
	put(x, y + 10, z, Color(0.1, 0.42, 0.22))


func _fence(x0: int, z0: int, x1: int, z1: int) -> void:
	for x in range(x0, x1 + 1):
		for z in [z0, z1]:
			_fence_bit(x, z, x % 3 == 0)
	for z in range(z0, z1 + 1):
		for x in [x0, x1]:
			_fence_bit(x, z, z % 3 == 0)


func _fence_bit(x: int, z: int, post: bool) -> void:
	if z == 32 and x % 20 in [0, 1, 2]:
		return
	if top(x, z) != GROUND:
		return
	var y := top(x, z) + 1
	if post:
		box(x, y, z, 1, 2, 1, WOOD_DARK)
	else:
		put(x, y, z, WOOD)


func _paddock() -> void:
	for x in range(73, 103):
		for z in range(19, 25):
			if top(x, z) == GROUND and rng.randf() < 0.15:
				put(x, GROUND, z, GRASS_DARK)
	_fence(72, 19, 102, 25)
	for h in [Vector2i(97, 20), Vector2i(99, 21)]:
		box(h.x, GROUND + 1, h.y, 2, 2, 2, HAY)
	_cow(76, 21)
	_cow(86, 22)
	_sheep(92, 21)
	_hen(81, 23)
	_hen(83, 21)
	_hen(33, 24)


func _fields() -> void:
	var plots := [
		Vector2i(44, 34),
		Vector2i(24, 34),
		Vector2i(64, 34),
		Vector2i(4, 34),
		Vector2i(84, 34),
		Vector2i(24, 48),
		Vector2i(44, 48),
		Vector2i(64, 48)
	]
	var kinds := ["pumpkin", "turnip", "carrot", "tomato"]
	_fence(2, 32, 100, 60)
	for n in plots.size():
		var p: Vector2i = plots[n]
		for x in 15:
			for z in 11:
				put(p.x + x, GROUND, p.y + z, SOIL_RIDGE if z % 3 == 1 else SOIL)
		var crops := rng.randi_range(6, 12)
		for i in crops:
			_crop(kinds[n % 4], rng.randi_range(0, 3), p.x + 1 + (i % 4) * 4, p.y + 1 + (i / 4) * 3)


func _crop(kind: String, stage: int, x: int, z: int) -> void:
	# a crop is a 3 x 2 cell: a sprout, a leafy plant, a plant with its fruit showing, then ripe
	var y := GROUND + 1
	if stage == 0:
		put(x + 1, y, z, LEAF_TOP)
		put(x + 1, y + 1, z, LEAF_TOP)
		return
	match kind:
		"pumpkin":
			box(x, y, z, 3, 1, 2, LEAF)
			box(x + 1, y + 1, z, 1, stage, 1, LEAF_MID)
			if stage >= 2:
				var ripe := stage == 3
				var fruit := Color(0.95, 0.48, 0.06) if ripe else Color(0.4, 0.62, 0.2)
				box(x, y + 1, z + 1, 3 if ripe else 2, 2, 2, fruit)
				put(x + 1, y + 3, z + 1, TRUNK)
		"turnip":
			box(x, y + (2 if stage == 3 else 0), z, 3, 1, 2, LEAF_MID)
			box(x + 1, y + (3 if stage == 3 else 1), z, 1, stage, 1, LEAF)
			if stage >= 2:
				box(x, y, z, 3, 1, 2, WHITE)
			if stage == 3:
				box(x, y + 1, z, 3, 1, 2, Color(0.6, 0.25, 0.6))
		"carrot":
			if stage == 3:
				box(x + 1, y, z, 1, 2, 2, Color(0.98, 0.5, 0.08))
			for k in 3:
				box(
					x + k,
					y + (2 if stage == 3 else 0),
					z + k % 2,
					1,
					stage + 1 - k % 2,
					1,
					LEAF_TOP if k != 1 else LEAF_MID
				)
		_:
			box(x + 1, y, z, 1, stage + 3, 1, WOOD)
			box(x, y + 1, z, 3, stage + 1, 1, LEAF)
			box(x, y + 1, z + 1, 1, stage, 1, LEAF_MID)
			if stage >= 2:
				var fruit := RED if stage == 3 else Color(0.5, 0.72, 0.2)
				put(x, y + stage + 1, z + 1, fruit)
				put(x + 2, y + 2, z + 1, fruit)
				put(x + 1, y + stage, z + 1, fruit)


func _clear(x: int, z: int) -> bool:
	for dx in range(-2, 3):
		for dz in range(-2, 3):
			var p := Vector2i(x + dx, z + dz)
			if top(p.x, p.y) != GROUND:
				return false
			var c: Color = bricks[Vector3i(p.x, GROUND, p.y)]
			if c in [LANE, SOIL, SOIL_RIDGE, STONE]:
				return false
			if bricks.has(Vector3i(p.x, GROUND + 1, p.y)):
				return false
	return true


func _forest() -> void:
	for i in 2600:
		var x := rng.randi_range(-2, W + 2)
		var z := rng.randi_range(-2, D + 2)
		var farmed := z > 29 and z < 61 and x > 1 and x < 101
		var penned := x > 70 and z > 16 and z < 27
		if _coast(x, z) > -0.1 or farmed or penned or not _clear(x, z):
			continue
		var roll := rng.randf()
		if roll < 0.3:
			_pine(x, z)
		elif roll < 0.5:
			_tree(x, z, rng.randi_range(2, 3), "autumn")
		else:
			_tree(x, z, rng.randi_range(2, 3), "green")


func _flowers() -> void:
	for i in 400:
		var x := rng.randi_range(0, W)
		var z := rng.randi_range(0, D)
		if (
			top(x, z) == GROUND
			and bricks.get(Vector3i(x, GROUND, z)) in [GRASS, GRASS_LIME, GRASS_DARK]
		):
			put(x, GROUND + 1, z, [Color(1, 0.55, 0.7), Color(1, 0.9, 0.3), WHITE][rng.randi() % 3])


func _figure(x: int, z: int, shirt: Color, hat: Color) -> void:
	var y := GROUND + 1
	box(x, y, z, 1, 2, 2, Color(0.2, 0.3, 0.62))
	box(x + 2, y, z, 1, 2, 2, Color(0.2, 0.3, 0.62))
	box(x, y + 2, z, 3, 2, 2, Color(0.22, 0.34, 0.68))
	box(x, y + 4, z, 3, 2, 2, shirt)
	box(x, y + 6, z, 3, 3, 3, SKIN)
	put(x, y + 7, z + 3, BLACK)
	put(x + 2, y + 7, z + 3, BLACK)
	box(x - 1, y + 9, z - 1, 5, 1, 5, hat)
	box(x, y + 10, z, 3, 1, 3, hat)


func _cow(x: int, z: int) -> void:
	var y := GROUND + 1
	for lx in [0, 3]:
		for lz in [0, 1]:
			put(x + lx, y, z + lz, WHITE)
	box(x, y + 1, z, 4, 2, 2, WHITE)
	box(x + 1, y + 2, z, 2, 1, 1, BLACK)
	put(x, y + 1, z + 1, BLACK)
	box(x + 4, y + 2, z, 2, 2, 2, WHITE)
	box(x + 5, y + 2, z, 1, 1, 2, Color(0.96, 0.66, 0.66))
	put(x + 4, y + 4, z, BLACK)
	put(x + 4, y + 4, z + 1, BLACK)


func _sheep(x: int, z: int) -> void:
	var y := GROUND + 1
	for lx in [0, 2]:
		put(x + lx, y, z, BLACK)
	box(x, y + 1, z - 1, 3, 2, 3, WHITE)
	box(x + 3, y + 1, z, 1, 2, 1, BLACK)


func _hen(x: int, z: int) -> void:
	var y := GROUND + 1
	box(x, y, z, 2, 1, 1, WHITE)
	put(x + 1, y + 1, z, WHITE)
	put(x + 1, y + 2, z, RED)
	put(x + 2, y + 1, z, Color(1, 0.7, 0.1))


func _folk() -> void:
	_figure(12, 24, Color(0.2, 0.55, 0.25), HAY)
	_figure(24, 26, Color(0.85, 0.3, 0.25), HAY)
	_figure(52, 26, Color(0.25, 0.4, 0.8), HAY)
	_figure(72, 28, Color(0.9, 0.7, 0.15), WOOD)


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


func _emit() -> void:
	var mat := StandardMaterial3D.new()
	mat.vertex_color_use_as_albedo = true
	mat.roughness = 0.32
	mat.metallic_specular = 0.65
	var shown := []
	var around := [
		Vector3i(0, 1, 0),
		Vector3i(1, 0, 0),
		Vector3i(-1, 0, 0),
		Vector3i(0, 0, 1),
		Vector3i(0, 0, -1)
	]
	for p in bricks:
		for n in around:
			if not bricks.has(p + n):
				shown.append(p)
				break
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.use_colors = true
	mm.mesh = _brick_mesh()
	mm.instance_count = shown.size()
	for i in shown.size():
		var p: Vector3i = shown[i]
		mm.set_instance_transform(i, Transform3D(Basis(), Vector3(p.x * B, p.y * H, p.z * B)))
		var c: Color = bricks[p]
		mm.set_instance_color(i, c * rng.randf_range(0.95, 1.04))
	var mi := MultiMeshInstance3D.new()
	mi.multimesh = mm
	mi.material_override = mat
	add_child(mi)
	printerr("bricks shown: ", shown.size(), " of ", bricks.size())


func _dust() -> void:
	var p := CPUParticles3D.new()
	p.amount = 140
	p.lifetime = 12.0
	p.preprocess = 12.0
	p.emission_shape = CPUParticles3D.EMISSION_SHAPE_BOX
	p.emission_box_extents = Vector3(13, 2.5, 8)
	p.position = Vector3(W * B / 2, 3, D * B / 2)
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
	var p := CPUParticles3D.new()
	p.amount = 20
	p.lifetime = 4.0
	p.preprocess = 4.0
	p.position = at
	p.direction = Vector3(0.3, 1, 0)
	p.spread = 12
	p.gravity = Vector3(0.12, 0.2, 0)
	p.initial_velocity_min = 0.25
	p.initial_velocity_max = 0.4
	var curve := Curve.new()
	curve.add_point(Vector2(0, 0.5))
	curve.add_point(Vector2(1, 1.8))
	p.scale_amount_curve = curve
	var fade := Gradient.new()
	fade.set_color(0, Color(0.95, 0.94, 0.92, 0.75))
	fade.set_color(1, Color(0.95, 0.94, 0.92, 0.0))
	p.color_ramp = fade
	var q := BoxMesh.new()
	q.size = Vector3(0.18, 0.18, 0.18)
	var m := StandardMaterial3D.new()
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.vertex_color_use_as_albedo = true
	m.roughness = 0.9
	q.material = m
	p.mesh = q
	add_child(p)
