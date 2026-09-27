extends Node3D

## One brick: a quarter of a tile across and a fifth of a tile tall, with a stud on top.
const B := 0.25
const H := 0.2
const W := 104
const D := 64

const GRASS := Color(0.36, 0.66, 0.12)
const GRASS2 := Color(0.56, 0.78, 0.1)
const DIRT := Color(0.74, 0.55, 0.32)
const SOIL := Color(0.45, 0.28, 0.16)
const LANE := Color(0.86, 0.74, 0.5)
const WATER := Color(0.05, 0.3, 0.78)
const WATER2 := Color(0.12, 0.42, 0.9)
const TRUNK := Color(0.45, 0.28, 0.14)
const LEAF := Color(0.12, 0.5, 0.2)
const LEAF2 := Color(0.2, 0.62, 0.22)
const WOOD := Color(0.62, 0.38, 0.2)
const ROOF := Color(0.72, 0.2, 0.15)
const STONE := Color(0.6, 0.6, 0.62)
const WHITE := Color(0.95, 0.95, 0.93)
const RED := Color(0.78, 0.12, 0.1)
const WINDOW := Color(1.0, 0.85, 0.4)

var bricks := {}
var rng := RandomNumberGenerator.new()


func _ready() -> void:
	rng.seed = 3
	_look()
	_island()
	_buildings()
	_fields()
	_trees()
	_folk()
	_emit()
	if "--shot" in OS.get_cmdline_user_args():
		for i in 30:
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


func _look() -> void:
	var e := Environment.new()
	e.background_mode = Environment.BG_COLOR
	e.background_color = Color(0.82, 0.84, 0.88)
	e.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	e.ambient_light_color = Color(0.9, 0.92, 1.0)
	e.ambient_light_energy = 0.42
	e.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	e.ssao_enabled = true
	e.ssao_radius = 0.6
	e.ssao_intensity = 2.0
	e.glow_enabled = true
	e.glow_intensity = 0.3
	e.adjustment_enabled = true
	e.adjustment_saturation = 1.25
	e.adjustment_contrast = 1.08
	var we := WorldEnvironment.new()
	we.environment = e
	add_child(we)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-55, 150, 0)
	sun.light_energy = 1.05
	sun.light_color = Color(1.0, 0.97, 0.92)
	sun.shadow_enabled = true
	sun.shadow_blur = 1.5
	add_child(sun)
	var cam := Camera3D.new()
	cam.fov = 30
	var centre := Vector3(W * B / 2, 0, D * B / 2 + 1)
	cam.position = centre + Vector3(0, sin(deg_to_rad(40)), cos(deg_to_rad(40))) * 26
	cam.rotation_degrees = Vector3(-40, 0, 0)
	var a := CameraAttributesPractical.new()
	a.dof_blur_far_enabled = true
	a.dof_blur_far_distance = 28
	a.dof_blur_far_transition = 8
	a.dof_blur_near_enabled = true
	a.dof_blur_near_distance = 21
	a.dof_blur_near_transition = 5
	a.dof_blur_amount = 0.14
	cam.attributes = a
	add_child(cam)


func _coast(x: int, z: int) -> float:
	var dx := (x - W / 2.0) / (W / 2.0 - 6)
	var dz := (z - D / 2.0) / (D / 2.0 - 5)
	var n := sin(x * 0.21) * 0.06 + cos(z * 0.27 + x * 0.05) * 0.07 + sin(x * 0.07 + z * 0.11) * 0.05
	return pow(abs(dx), 4) + pow(abs(dz), 4) - 1.0 + n


func _island() -> void:
	for x in range(-24, W + 24):
		for z in range(-20, D + 20):
			var on := _coast(x, z) < 0
			if not on:
				var crest := sin(x * 0.35 + z * 0.9) > 0.97 and rng.randf() < 0.7
				put(x, -1, z, WHITE if crest else (WATER2 if rng.randf() < 0.12 else WATER))
				continue
			var patch := sin(x * 0.13) + cos(z * 0.17 + x * 0.05) + rng.randf() * 0.6
			var top := GRASS2 if patch > 1.0 else (Color(0.2, 0.5, 0.12) if patch < -0.9 else GRASS)
			var edge := _coast(x, z)
			var drop := 0 if edge < -0.18 else (1 if edge < -0.07 else 2)
			for y in range(-1, 2 - drop):
				put(x, y, z, DIRT)
			put(x, 2 - drop, z, top)
	# the lane across the middle, and the road in from the north
	for x in range(0, W):
		for z in range(26, 32):
			if bricks.has(Vector3i(x, 2, z)):
				put(x, 2, z, LANE)
	for z in range(0, 26):
		for x in range(64, 70):
			if bricks.has(Vector3i(x, 2, z)):
				put(x, 2, z, LANE)


func _buildings() -> void:
	# the farmhouse: wooden walls, windows, a door, a stepped red roof and a stone chimney
	box(10, 3, 12, 18, 6, 10, WOOD)
	for x in [12, 16, 22, 25]:
		box(x, 5, 21, 2, 2, 1, WINDOW)
	box(19, 3, 21, 2, 4, 1, TRUNK)
	for s in 5:
		box(9, 9 + s, 11 + s, 20, 1, 12 - s * 2, ROOF)
	box(11, 9, 14, 2, 8, 2, STONE)
	# the barn: red walls with white trim, a slate roof, and its grey silo
	box(80, 3, 8, 14, 7, 10, RED)
	box(80, 3, 17, 14, 1, 1, WHITE)
	box(84, 3, 17, 6, 5, 1, WOOD)
	for s in 4:
		box(79, 10 + s, 7 + s, 16, 1, 12 - s * 2, Color(0.32, 0.34, 0.38))
	for y in range(3, 18):
		for x in range(95, 100):
			for z in range(9, 14):
				var d := Vector2(x - 97, z - 11).length()
				if d <= 2.3 and d > 1.2 or y > 15 and d <= 2.3:
					put(x, y, z, STONE if y < 16 else Color(0.75, 0.75, 0.78))
	# the well: a ring of stone, two posts and a little roof
	for x in range(46, 51):
		for z in range(20, 25):
			var d := Vector2(x - 48, z - 22).length()
			if d <= 2.2 and d > 1.0:
				box(x, 3, z, 1, 2, 1, STONE)
	put(48, 3, 22, WATER)
	box(46, 5, 22, 1, 4, 1, TRUNK)
	box(50, 5, 22, 1, 4, 1, TRUNK)
	box(45, 9, 21, 7, 1, 3, ROOF)


func _fields() -> void:
	var kinds := [Color(0.95, 0.5, 0.08), Color(0.85, 0.3, 0.7), Color(0.98, 0.55, 0.1), Color(0.9, 0.12, 0.1)]
	var plots := [Vector2i(44, 36), Vector2i(24, 36), Vector2i(64, 36), Vector2i(8, 36), Vector2i(84, 36), Vector2i(44, 50), Vector2i(24, 50), Vector2i(64, 50)]
	for n in plots.size():
		var p: Vector2i = plots[n]
		for x in 16:
			for z in 11:
				put(p.x + x, 2, p.y + z, SOIL if z % 4 != 3 else DIRT)
		var crops := rng.randi_range(5, 12)
		for i in crops:
			var cx: int = p.x + 1 + (i % 4) * 4
			var cz: int = p.y + (i / 4) * 4
			var stage := rng.randi_range(0, 3)
			put(cx, 3, cz, LEAF2)
			if stage >= 1:
				put(cx + 1, 3, cz, LEAF)
				put(cx, 3, cz + 1, LEAF)
			if stage >= 2:
				put(cx, 4, cz, LEAF2)
			if stage == 3:
				box(cx + 1, 3, cz + 1, 2, 2, 2, kinds[n % 4])
		for x in range(-1, 17):
			if x % 3 == 0:
				box(p.x + x, 3, p.y + 11, 1, 2, 1, WOOD)


func _tree(x: int, z: int, big: bool, apples: bool) -> void:
	var h := 5 if big else 3
	box(x, 3, z, 1, h, 1, TRUNK)
	var r := 3 if big else 2
	for dx in range(-r, r + 1):
		for dy in range(0, r + 2):
			for dz in range(-r, r + 1):
				var d := Vector3(dx, dy - r * 0.6, dz).length()
				if d <= r + 0.3 and rng.randf() < 0.92:
					var c := LEAF2 if rng.randf() < 0.3 else LEAF
					if apples and rng.randf() < 0.1:
						c = RED
					put(x + dx, 3 + h + dy, z + dz, c)


func _trees() -> void:
	_tree(56, 20, true, true)
	for i in 900:
		var x := rng.randi_range(0, W)
		var z := rng.randi_range(0, D)
		var edge := _coast(x, z)
		var clear := (z > 23 and z < 34) or (x > 61 and x < 73 and z < 26) or (z > 33 and x > 5 and x < 102)
		var building := (x > 6 and x < 31 and z > 8 and z < 26) or (x > 76 and x < 102 and z > 5 and z < 26)
		building = building or (x > 42 and x < 60 and z > 16 and z < 26)
		if edge < -0.08 and not clear and not building and not _crowded(x, z):
			if rng.randf() < 0.4:
				_pine(x, z)
			else:
				_tree(x, z, rng.randf() < 0.5, false)


func _crowded(x: int, z: int) -> bool:
	for p in bricks:
		if p.y > 5 and abs(p.x - x) < 3 and abs(p.z - z) < 3:
			return true
	return false


func _pine(x: int, z: int) -> void:
	var base := 3
	box(x, base, z, 1, 2, 1, TRUNK)
	for s in 4:
		var r := 3 - s
		for dx in range(-r, r + 1):
			for dz in range(-r, r + 1):
				if abs(dx) + abs(dz) <= r + 1:
					put(x + dx, base + 2 + s * 2, z + dz, Color(0.08, 0.38, 0.18))
					put(x + dx, base + 3 + s * 2, z + dz, Color(0.1, 0.44, 0.2))


func _figure(x: int, z: int, shirt: Color) -> void:
	box(x, 3, z, 2, 2, 1, Color(0.2, 0.3, 0.6))
	box(x, 5, z, 2, 2, 1, shirt)
	box(x, 7, z, 2, 2, 1, Color(0.96, 0.78, 0.62))
	box(x - 1, 9, z - 1, 4, 1, 3, Color(0.85, 0.72, 0.42))


func _folk() -> void:
	_figure(14, 25, Color(0.2, 0.55, 0.25))
	_figure(30, 27, Color(0.8, 0.3, 0.2))
	_figure(52, 26, Color(0.2, 0.55, 0.25))
	_figure(72, 29, Color(0.25, 0.4, 0.8))
	for c in [Vector2i(84, 21), Vector2i(91, 23)]:
		box(c.x, 3, c.y, 4, 2, 2, WHITE)
		box(c.x + 1, 4, c.y, 1, 1, 2, Color(0.1, 0.1, 0.1))
		box(c.x + 4, 4, c.y, 1, 2, 2, WHITE)
		box(c.x + 5, 4, c.y, 1, 1, 2, Color(0.95, 0.65, 0.65))
		for lx in [0, 3]:
			box(c.x + lx, 2, c.y, 1, 1, 1, WHITE)
	for c in [Vector2i(33, 22), Vector2i(36, 24), Vector2i(78, 22)]:
		box(c.x, 3, c.y, 2, 2, 1, WHITE)
		put(c.x + 1, 5, c.y, RED)


func _brick_mesh() -> Mesh:
	var st := SurfaceTool.new()
	var cube := BoxMesh.new()
	cube.size = Vector3(B * 0.98, H, B * 0.98)
	st.append_from(cube, 0, Transform3D(Basis(), Vector3(0, H / 2, 0)))
	var stud := CylinderMesh.new()
	stud.top_radius = B * 0.3
	stud.bottom_radius = B * 0.3
	stud.height = H * 0.25
	stud.radial_segments = 10
	stud.rings = 1
	st.append_from(stud, 0, Transform3D(Basis(), Vector3(0, H * 1.12, 0)))
	return st.commit()


func _emit() -> void:
	var mesh := _brick_mesh()
	var mat := StandardMaterial3D.new()
	mat.vertex_color_use_as_albedo = true
	mat.roughness = 0.35
	mat.metallic_specular = 0.6
	var visible := []
	for p in bricks:
		# a brick with a brick on top of it and on every side shows nothing but its stud; skip it
		var hidden := true
		for n in [Vector3i(0, 1, 0), Vector3i(1, 0, 0), Vector3i(-1, 0, 0), Vector3i(0, 0, 1), Vector3i(0, 0, -1)]:
			if not bricks.has(p + n):
				hidden = false
				break
		if not hidden:
			visible.append(p)
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.use_colors = true
	mm.mesh = mesh
	mm.instance_count = visible.size()
	for i in visible.size():
		var p: Vector3i = visible[i]
		mm.set_instance_transform(i, Transform3D(Basis(), Vector3(p.x * B, p.y * H, p.z * B)))
		var c: Color = bricks[p]
		mm.set_instance_color(i, c * rng.randf_range(0.96, 1.04))
	var mi := MultiMeshInstance3D.new()
	mi.multimesh = mm
	mi.material_override = mat
	add_child(mi)
	printerr("bricks drawn: ", visible.size(), " of ", bricks.size())
