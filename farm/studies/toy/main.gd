extends Node3D

const W := 26.0
const D := 16.0

const GRASS := Color(0.36, 0.6, 0.24)
const GRASS_LIGHT := Color(0.5, 0.72, 0.3)
const PATH := Color(0.93, 0.88, 0.74)
const CLIFF := Color(0.86, 0.66, 0.36)
const SOIL := Color(0.45, 0.3, 0.2)
const LEAF := Color(0.26, 0.55, 0.2)
const LEAF_DARK := Color(0.16, 0.38, 0.18)
const TRUNK := Color(0.5, 0.33, 0.2)
const SKIN := Color(0.98, 0.84, 0.72)

var rng := RandomNumberGenerator.new()
var bumpy: NoiseTexture2D
## Where [method shape] puts what it makes: the scene, or a figure being built.
var into: Node3D


func _ready() -> void:
	rng.seed = 5
	bumpy = _noise_normals(0.06, 6.0)
	_light()
	_backdrop()
	_land()
	_water()
	_homes()
	_well(Vector3(12.5, 0, 5.6))
	_fields()
	_trees()
	_folk()
	if "--shot" in OS.get_cmdline_user_args():
		for i in 40:
			await get_tree().process_frame
		await RenderingServer.frame_post_draw
		get_viewport().get_texture().get_image().save_png("res://shot.png")
		get_tree().quit()


func _noise_normals(freq: float, strength: float) -> NoiseTexture2D:
	var n := FastNoiseLite.new()
	n.frequency = freq
	n.fractal_octaves = 3
	var t := NoiseTexture2D.new()
	t.noise = n
	t.width = 512
	t.height = 512
	t.seamless = true
	t.as_normal_map = true
	t.bump_strength = strength
	return t


func paint(c: Color, rough := 0.55, bump := false) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.albedo_color = c
	m.roughness = rough
	if bump:
		m.normal_enabled = true
		m.normal_texture = bumpy
		m.normal_scale = 1.2
		m.uv1_triplanar = true
		m.uv1_scale = Vector3(0.6, 0.6, 0.6)
	return m


func shape(mesh: Mesh, at: Vector3, c: Color, s := Vector3.ONE, bump := false) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.position = at
	mi.scale = s
	mi.material_override = paint(c, 0.55, bump)
	(into if into else self).add_child(mi)
	return mi


func ball(r: float, at: Vector3, c: Color, s := Vector3.ONE, bump := false) -> MeshInstance3D:
	var m := SphereMesh.new()
	m.radius = r
	m.height = r * 2
	return shape(m, at, c, s, bump)


func tube(r_top: float, r_bottom: float, h: float, at: Vector3, c: Color) -> MeshInstance3D:
	var m := CylinderMesh.new()
	m.top_radius = r_top
	m.bottom_radius = r_bottom
	m.height = h
	return shape(m, at + Vector3(0, h / 2, 0), c)


func _light() -> void:
	var e := Environment.new()
	e.background_mode = Environment.BG_COLOR
	e.background_color = Color(0.2, 0.45, 0.8)
	e.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	e.ambient_light_color = Color(0.85, 0.88, 1.0)
	e.ambient_light_energy = 0.4
	e.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	e.ssao_enabled = true
	e.ssao_radius = 1.0
	e.ssao_intensity = 2.2
	e.glow_enabled = true
	e.glow_intensity = 0.25
	e.adjustment_enabled = true
	e.adjustment_saturation = 1.2
	e.adjustment_contrast = 1.05
	var we := WorldEnvironment.new()
	we.environment = e
	add_child(we)
	# display-case light: a key from above-front, soft shadows, and a warm fill
	var key := DirectionalLight3D.new()
	key.rotation_degrees = Vector3(-60, -20, 0)
	key.light_energy = 1.0
	key.shadow_enabled = true
	key.shadow_blur = 3.0
	add_child(key)
	var fill := DirectionalLight3D.new()
	fill.rotation_degrees = Vector3(-25, 150, 0)
	fill.light_energy = 0.35
	fill.light_color = Color(1.0, 0.85, 0.7)
	add_child(fill)
	var cam := Camera3D.new()
	cam.fov = 34
	cam.position = Vector3(13, 15.5, 30.5)
	cam.rotation_degrees = Vector3(-31, 0, 0)
	var a := CameraAttributesPractical.new()
	a.dof_blur_far_enabled = true
	a.dof_blur_far_distance = 33
	a.dof_blur_far_transition = 12
	a.dof_blur_near_enabled = true
	a.dof_blur_near_distance = 20
	a.dof_blur_near_transition = 4
	a.dof_blur_amount = 0.1
	cam.attributes = a
	add_child(cam)


func _backdrop() -> void:
	# the painted wall behind the case: a sky, and flat cartoon clouds stuck to it
	var wall := MeshInstance3D.new()
	var q := QuadMesh.new()
	q.size = Vector2(70, 26)
	wall.mesh = q
	wall.position = Vector3(13, 6, -9)
	var sky := ShaderMaterial.new()
	var sh := Shader.new()
	sh.code = """
shader_type spatial;
render_mode unshaded;
void fragment() {
	vec3 top = vec3(0.12, 0.38, 0.78);
	vec3 low = vec3(0.35, 0.65, 0.95);
	ALBEDO = mix(low, top, pow(UV.y < 0.5 ? 1.0 - UV.y * 2.0 : 0.0, 0.7));
}
"""
	sky.shader = sh
	wall.material_override = sky
	add_child(wall)
	for c in [Vector3(-2, 11, -8.8), Vector3(8, 13, -8.8), Vector3(20, 11.5, -8.8), Vector3(30, 13.5, -8.8), Vector3(13, 9.5, -8.8)]:
		for i in 4:
			var off := Vector3(i * 0.9 - 1.3, (0.35 if i % 3 != 0 else 0.0), 0)
			ball(0.8 + (0.25 if i in [1, 2] else 0.0), c + off, Color(1, 1, 1), Vector3(1, 1, 0.15))
	# far hills painted flat in two greens, and one snowy peak
	for h in [[Vector3(2, -1, -8), 7.0, Color(0.33, 0.5, 0.3)], [Vector3(26, -1, -8), 8.0, Color(0.3, 0.46, 0.3)], [Vector3(14, -2, -8.5), 9.0, Color(0.36, 0.42, 0.62)]]:
		ball(h[1], h[0], h[2], Vector3(1.4, 0.55, 0.05))
	ball(1.6, Vector3(14, 3.0, -8.4), Color(0.96, 0.97, 1.0), Vector3(1.4, 0.7, 0.05))


func _height(x: float, z: float) -> float:
	# a gentle rise to a hill at the back left, the rest near flat
	var hill := exp(-(pow((x - 6) / 6.0, 2) + pow((z - 0.5) / 3.5, 2))) * 2.2
	var hill2 := exp(-(pow((x - 22) / 5.0, 2) + pow((z + 0.5) / 3.0, 2))) * 1.4
	return hill + hill2 + sin(x * 0.7) * cos(z * 0.6) * 0.06


func _ground_colour(x: float, z: float) -> Color:
	var on_lane := z > 6.1 and z < 7.9
	var on_road := x > 16 and x < 17.6 and z < 6.5
	var wind: bool = abs(z - (2.5 + sin(x * 0.45) * 1.2)) < 0.45 and x < 15.5
	if on_lane or on_road or wind:
		return PATH
	var t := sin(x * 0.9) * cos(z * 1.1) * 0.5 + 0.5
	return GRASS.lerp(GRASS_LIGHT, t * 0.5)


func _land() -> void:
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	var step := 0.2
	var nx := int(W / step)
	var nz := int((D + 3) / step)
	for i in nx:
		for j in nz:
			var quad := []
			for c in [Vector2(0, 0), Vector2(1, 0), Vector2(1, 1), Vector2(0, 0), Vector2(1, 1), Vector2(0, 1)]:
				var x: float = (i + c.x) * step
				var z: float = (j + c.y) * step - 3
				quad.append(Vector3(x, _height(x, z), z))
			for v in quad:
				st.set_color(_ground_colour(v.x, v.z))
				st.set_uv(Vector2(v.x, v.z) * 0.3)
				st.add_vertex(v)
	# the cut edge of the base: sandy cliffs down to the water at the front and sides
	for edge in [[Vector3(0, 0, D), Vector3(W, 0, D)], [Vector3(0, 0, -3), Vector3(0, 0, D)], [Vector3(W, 0, D), Vector3(W, 0, -3)]]:
		var a: Vector3 = edge[0]
		var b: Vector3 = edge[1]
		var n := 60
		for k in n:
			var p0 := a.lerp(b, float(k) / n)
			var p1 := a.lerp(b, float(k + 1) / n)
			var t0 := Vector3(p0.x, _height(p0.x, p0.z), p0.z)
			var t1 := Vector3(p1.x, _height(p1.x, p1.z), p1.z)
			var b0 := Vector3(p0.x, -1.6, p0.z)
			var b1 := Vector3(p1.x, -1.6, p1.z)
			for v in [t0, b0, b1, t0, b1, t1]:
				st.set_color(CLIFF.darkened(rng.randf() * 0.08))
				st.set_uv(Vector2(v.x + v.z, v.y) * 0.4)
				st.add_vertex(v)
	st.generate_normals()
	var mi := MeshInstance3D.new()
	mi.mesh = st.commit()
	var m := paint(Color.WHITE, 0.8, true)
	m.vertex_color_use_as_albedo = true
	m.vertex_color_is_srgb = true
	m.normal_scale = 0.5
	mi.material_override = m
	add_child(mi)


func _water() -> void:
	var m := PlaneMesh.new()
	m.size = Vector2(60, 12)
	var w := shape(m, Vector3(13, -1.1, D + 5.5), Color(0.45, 0.5, 0.82))
	var mat: StandardMaterial3D = w.material_override
	mat.roughness = 0.15
	mat.metallic_specular = 0.8


func _door(at: Vector3, c: Color) -> void:
	shape(BoxMesh.new(), at, c, Vector3(0.35, 0.55, 0.08))
	ball(0.2, at + Vector3(0, 0.27, 0), c, Vector3(0.9, 0.9, 0.4))


func _carrot_house(at: Vector3) -> void:
	var body := CylinderMesh.new()
	body.top_radius = 1.0
	body.bottom_radius = 0.35
	body.height = 2.6
	shape(body, at + Vector3(0, 1.3, 0), Color(0.95, 0.45, 0.12), Vector3.ONE, true)
	ball(1.0, at + Vector3(0, 2.6, 0), Color(0.95, 0.45, 0.12), Vector3(1, 0.3, 1))
	for i in 5:
		var a := i * TAU / 5
		var leaf := ball(0.35, at + Vector3(cos(a) * 0.3, 3.2, sin(a) * 0.3), LEAF, Vector3(0.6, 1.8, 0.6))
		leaf.rotation = Vector3(sin(a) * 0.4, 0, -cos(a) * 0.4)
	_door(at + Vector3(0, 0.45, 0.62), Color(0.55, 0.3, 0.15))
	ball(0.18, at + Vector3(0.3, 1.7, 0.85), Color(1, 0.9, 0.45), Vector3(1, 1, 0.4))


func _turnip_house(at: Vector3) -> void:
	ball(1.2, at + Vector3(0, 1.1, 0), Color(0.97, 0.96, 0.92), Vector3(1, 0.95, 1), true)
	ball(1.05, at + Vector3(0, 1.75, 0), Color(0.62, 0.3, 0.6), Vector3(1, 0.5, 1))
	for i in 6:
		var a := i * TAU / 6
		var leaf := ball(0.45, at + Vector3(cos(a) * 0.45, 2.7, sin(a) * 0.45), LEAF_DARK, Vector3(1.0, 0.35, 1.6), true)
		leaf.rotation = Vector3(0, -a, 0.6)
	_door(at + Vector3(0, 0.4, 1.12), Color(0.45, 0.6, 0.3))
	ball(0.2, at + Vector3(-0.5, 1.2, 1.0), Color(1, 0.9, 0.45), Vector3(1, 1, 0.4))


func _pepper_house(at: Vector3) -> void:
	for i in 4:
		var a := i * TAU / 4 + 0.4
		ball(0.75, at + Vector3(cos(a) * 0.35, 1.1, sin(a) * 0.35), Color(0.98, 0.78, 0.12), Vector3(1, 1.45, 1))
	tube(0.15, 0.2, 0.5, at + Vector3(0, 2.1, 0), Color(0.3, 0.5, 0.2))
	_door(at + Vector3(0, 0.4, 0.95), Color(0.55, 0.3, 0.15))


func _homes() -> void:
	_carrot_house(Vector3(3.2, 0.3, 4.2))
	_turnip_house(Vector3(7.2, 0.4, 4.0))
	_pepper_house(Vector3(10.2, 0.2, 4.3))
	# the barn: a red box, white trim, a red roof and a round silo
	var barn := Vector3(21.5, _height(21.5, 3), 3.0)
	shape(BoxMesh.new(), barn + Vector3(0, 1.1, 0), Color(0.82, 0.18, 0.14), Vector3(3.2, 2.2, 2.4))
	var roof := PrismMesh.new()
	roof.size = Vector3(3.6, 1.3, 2.7)
	shape(roof, barn + Vector3(0, 2.85, 0), Color(0.95, 0.3, 0.2))
	shape(BoxMesh.new(), barn + Vector3(0, 0.7, 1.21), Color(0.96, 0.94, 0.9), Vector3(1.3, 1.4, 0.05))
	tube(0.75, 0.75, 3.8, barn + Vector3(2.4, 0, -0.2), Color(0.8, 0.82, 0.86))
	ball(0.75, barn + Vector3(2.4, 3.8, -0.2), Color(0.7, 0.72, 0.76), Vector3(1, 0.6, 1))


func _well(at: Vector3) -> void:
	tube(0.7, 0.75, 0.7, at, Color(0.7, 0.7, 0.72))
	tube(0.55, 0.55, 0.05, at + Vector3(0, 0.66, 0), Color(0.25, 0.45, 0.85))
	for x in [-0.65, 0.65]:
		shape(BoxMesh.new(), at + Vector3(x, 1.2, 0), TRUNK, Vector3(0.12, 1.4, 0.12))
	var roof := PrismMesh.new()
	roof.size = Vector3(1.8, 0.6, 1.2)
	shape(roof, at + Vector3(0, 2.2, 0), Color(0.85, 0.3, 0.2))


func _fields() -> void:
	var plots := [Vector2(11, 9), Vector2(6, 9), Vector2(16, 9), Vector2(1, 9), Vector2(21, 9), Vector2(6, 12.8), Vector2(11, 12.8), Vector2(16, 12.8)]
	for n in plots.size():
		var p: Vector2 = plots[n]
		shape(BoxMesh.new(), Vector3(p.x + 2, 0.08, p.y + 1.5), SOIL, Vector3(4.2, 0.2, 3.2), true)
		var crops := rng.randi_range(6, 12)
		for i in crops:
			var at := Vector3(p.x + 0.55 + (i % 4) * 0.95, 0.18, p.y + 0.5 + (i / 4) * 1.0)
			_crop(n % 4, rng.randi_range(0, 3), at)


func _crop(kind: int, stage: int, at: Vector3) -> void:
	var g := 0.4 + stage * 0.2
	match kind:
		0:
			ball(0.32 * g, at + Vector3(0, 0.2 * g, 0), Color(0.55, 0.78, 0.4), Vector3(1, 0.85, 1), true)
			ball(0.4 * g, at + Vector3(0, 0.15 * g, 0), Color(0.35, 0.6, 0.3), Vector3(1.2, 0.5, 1.2), true)
		1:
			if stage == 3:
				ball(0.3, at + Vector3(0, 0.22, 0), Color(0.95, 0.5, 0.1), Vector3(1.2, 0.85, 1.2), true)
			for k in 3:
				ball(0.16 * g, at + Vector3(k * 0.12 - 0.12, 0.1, -0.15), LEAF, Vector3(1, 0.4, 1.4))
		2:
			tube(0.02, 0.02, 0.9 * g, at, TRUNK)
			ball(0.25 * g, at + Vector3(0, 0.6 * g, 0), LEAF, Vector3(0.8, 1.4, 0.8), true)
			if stage >= 2:
				for k in 3:
					ball(0.08, at + Vector3(k * 0.1 - 0.1, 0.35 + k * 0.12, 0.12), Color(0.9, 0.15, 0.1))
		_:
			for k in 5:
				var a := k * TAU / 5
				var leaf := ball(0.14 * g, at + Vector3(cos(a) * 0.08, 0.3 * g, sin(a) * 0.08), LEAF_DARK, Vector3(0.4, 1.8, 0.4))
				leaf.rotation = Vector3(sin(a) * 0.3, 0, -cos(a) * 0.3)
			if stage == 3:
				ball(0.16, at + Vector3(0, 0.05, 0), Color(0.62, 0.3, 0.6))


func _round_tree(at: Vector3, s: float) -> void:
	tube(0.12 * s, 0.18 * s, 1.1 * s, at, TRUNK)
	var crown := at + Vector3(0, 1.6 * s, 0)
	ball(0.85 * s, crown, LEAF, Vector3(1, 1.05, 1), true)
	for i in 5:
		var a := rng.randf() * TAU
		ball(0.45 * s, crown + Vector3(cos(a) * 0.55 * s, rng.randf_range(-0.3, 0.4) * s, sin(a) * 0.55 * s), LEAF.lerp(LEAF_DARK, rng.randf() * 0.5), Vector3.ONE, true)


func _pine(at: Vector3, s: float) -> void:
	tube(0.08 * s, 0.1 * s, 0.4 * s, at, TRUNK)
	for i in 3:
		var c := CylinderMesh.new()
		c.top_radius = 0.0
		c.bottom_radius = (0.65 - i * 0.15) * s
		c.height = 0.9 * s
		shape(c, at + Vector3(0, (0.75 + i * 0.5) * s, 0), LEAF_DARK)


func _trees() -> void:
	_round_tree(Vector3(14.4, 0, 5.0), 1.3)
	for i in 60:
		var x := rng.randf_range(0, W)
		var z := rng.randf_range(-2.8, 3.8)
		var busy := (x > 1.5 and x < 11.8 and z > 2.6) or (x > 18.8 and x < 25.5 and z > 0.5) or (x > 15.6 and x < 18.2)
		if busy:
			continue
		var at := Vector3(x, _height(x, z) - 0.05, z)
		if rng.randf() < 0.45:
			_pine(at, rng.randf_range(0.9, 1.5))
		else:
			_round_tree(at, rng.randf_range(0.7, 1.1))
	for z in [8.5, 12.3]:
		for x in [0.3, 25.7]:
			_round_tree(Vector3(x, 0, z), 0.8)


func _figure(foot: Vector3, shirt: Color, hat: Color, face := 0.0) -> void:
	var root := Node3D.new()
	root.position = foot
	root.scale = Vector3.ONE * 1.5
	add_child(root)
	into = root
	var at := Vector3.ZERO
	var body := CapsuleMesh.new()
	body.radius = 0.2
	body.height = 0.6
	shape(body, at + Vector3(0, 0.32, 0), shirt)
	var head := ball(0.26, at + Vector3(0, 0.82, 0), SKIN)
	head.rotation.y = face
	ball(0.035, at + Vector3(-0.09, 0.85, 0.23), Color(0.1, 0.1, 0.1))
	ball(0.035, at + Vector3(0.09, 0.85, 0.23), Color(0.1, 0.1, 0.1))
	ball(0.045, at + Vector3(-0.15, 0.77, 0.2), Color(1, 0.55, 0.5), Vector3(1, 0.6, 0.4))
	ball(0.045, at + Vector3(0.15, 0.77, 0.2), Color(1, 0.55, 0.5), Vector3(1, 0.6, 0.4))
	tube(0.36, 0.36, 0.04, at + Vector3(0, 1.0, 0), hat)
	ball(0.2, at + Vector3(0, 1.04, 0), hat, Vector3(1, 0.7, 1))
	into = null


func _folk() -> void:
	_figure(Vector3(3.4, 0.05, 5.6), Color(0.25, 0.45, 0.8), Color(0.9, 0.78, 0.45))
	_figure(Vector3(7.8, 0.1, 5.6), Color(0.2, 0.6, 0.3), Color(0.9, 0.78, 0.45))
	_figure(Vector3(13.3, 0.0, 6.6), Color(0.9, 0.35, 0.3), Color(0.95, 0.95, 0.95))
	_figure(Vector3(18.5, 0.0, 7.2), Color(0.95, 0.75, 0.2), Color(0.6, 0.35, 0.2))
	# a cow and three hens by the barn
	var cow := Vector3(19.3, _height(19.3, 5), 5.0)
	var body := CapsuleMesh.new()
	body.radius = 0.35
	body.height = 1.2
	var c := shape(body, cow + Vector3(0, 0.55, 0), Color(0.97, 0.96, 0.93))
	c.rotation.z = PI / 2
	ball(0.15, cow + Vector3(0.1, 0.8, 0.25), Color(0.2, 0.15, 0.12), Vector3(1, 1, 0.5))
	ball(0.27, cow + Vector3(0.7, 0.75, 0), Color(0.97, 0.96, 0.93))
	ball(0.16, cow + Vector3(0.9, 0.66, 0), Color(1, 0.7, 0.7), Vector3(0.8, 0.8, 1.2))
	for leg in [Vector3(-0.3, 0, -0.15), Vector3(-0.3, 0, 0.15), Vector3(0.3, 0, -0.15), Vector3(0.3, 0, 0.15)]:
		tube(0.07, 0.07, 0.35, cow + leg, Color(0.97, 0.96, 0.93))
	for h in [Vector3(22.8, 0, 5.3), Vector3(23.5, 0, 5.8), Vector3(20.6, 0, 5.9)]:
		var at: Vector3 = h + Vector3(0, _height(h.x, h.z), 0)
		ball(0.2, at + Vector3(0, 0.2, 0), Color(1, 1, 1), Vector3(1.2, 1, 1))
		ball(0.12, at + Vector3(0.18, 0.38, 0), Color(1, 1, 1))
		ball(0.05, at + Vector3(0.18, 0.5, 0), Color(0.9, 0.1, 0.1))
		ball(0.04, at + Vector3(0.3, 0.38, 0), Color(1, 0.7, 0.1))
