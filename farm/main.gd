extends Node3D

const CENTRE := Vector3(13, 0, 8)
const PITCH := 42.0
const DISTANCE := 27.0

var rng := RandomNumberGenerator.new()


func _ready() -> void:
	rng.seed = 7
	_environment()
	_camera()
	_sun()
	_ground()
	_world()
	_dust()
	_smoke(Vector3(2.45, 6.0, 5.4))
	if "--shot" in OS.get_cmdline_user_args():
		for i in 40:
			await get_tree().process_frame
		await RenderingServer.frame_post_draw
		get_viewport().get_texture().get_image().save_png("res://shot.png")
		get_tree().quit()


func tex(name: String) -> ImageTexture:
	var img := Image.load_from_file(ProjectSettings.globalize_path("res://art/%s.png" % name))
	img.generate_mipmaps()
	return ImageTexture.create_from_image(img)


## A sprite standing on the ground at [at], [height] tiles tall, turned to face the camera.
func sprite(name: String, at: Vector3, height: float) -> Sprite3D:
	var s := Sprite3D.new()
	s.texture = tex(name)
	s.pixel_size = height / s.texture.get_height()
	s.offset = Vector2(0, s.texture.get_height() / 2.0)
	s.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
	s.alpha_cut = SpriteBase3D.ALPHA_CUT_DISCARD
	s.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST_WITH_MIPMAPS
	s.shaded = true
	s.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_ON
	# upright sprites seen from above look squat; stretch them back by the camera's pitch
	s.scale = Vector3(1, 1.0 / cos(deg_to_rad(PITCH)) * 0.85, 1)
	s.position = at
	add_child(s)
	return s


func _environment() -> void:
	var e := Environment.new()
	e.background_mode = Environment.BG_COLOR
	e.background_color = Color(0.55, 0.62, 0.45)
	e.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	e.ambient_light_color = Color(0.62, 0.68, 0.55)
	e.ambient_light_energy = 0.55
	e.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	e.tonemap_exposure = 1.05
	e.glow_enabled = true
	e.glow_intensity = 0.7
	e.glow_bloom = 0.12
	e.glow_hdr_threshold = 0.85
	e.glow_blend_mode = Environment.GLOW_BLEND_MODE_SOFTLIGHT
	e.ssao_enabled = true
	e.ssao_radius = 1.2
	e.ssao_intensity = 1.6
	e.volumetric_fog_enabled = true
	e.volumetric_fog_density = 0.0045
	e.volumetric_fog_albedo = Color(1.0, 0.9, 0.72)
	e.volumetric_fog_anisotropy = 0.6
	e.volumetric_fog_length = 70.0
	e.adjustment_enabled = true
	e.adjustment_saturation = 1.12
	e.adjustment_contrast = 1.06
	var w := WorldEnvironment.new()
	w.environment = e
	add_child(w)


func _camera() -> void:
	var c := Camera3D.new()
	c.fov = 32
	var back := Vector3(0, sin(deg_to_rad(PITCH)), cos(deg_to_rad(PITCH))) * DISTANCE
	c.position = CENTRE + back
	c.rotation_degrees = Vector3(-PITCH, 0, 0)
	var a := CameraAttributesPractical.new()
	a.dof_blur_far_enabled = true
	a.dof_blur_far_distance = DISTANCE + 5.0
	a.dof_blur_far_transition = 10.0
	a.dof_blur_near_enabled = true
	a.dof_blur_near_distance = DISTANCE - 7.0
	a.dof_blur_near_transition = 5.0
	a.dof_blur_amount = 0.12
	c.attributes = a
	add_child(c)


func _sun() -> void:
	var s := DirectionalLight3D.new()
	s.rotation_degrees = Vector3(-34, 135, 0)
	s.light_color = Color(1.0, 0.9, 0.74)
	s.light_energy = 1.5
	s.light_volumetric_fog_energy = 1.6
	s.shadow_enabled = true
	s.shadow_blur = 2.0
	s.directional_shadow_max_distance = 80.0
	add_child(s)


func _ground() -> void:
	var m := MeshInstance3D.new()
	var p := PlaneMesh.new()
	p.size = Vector2(40, 30)
	m.mesh = p
	m.position = Vector3(13, 0, 7)
	var mat := StandardMaterial3D.new()
	mat.albedo_texture = tex("ground")
	mat.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST_WITH_MIPMAPS
	mat.roughness = 1.0
	m.material_override = mat
	add_child(m)


func _world() -> void:
	sprite("farmhouse", Vector3(4.4, 0, 5.2), 6.2)
	sprite("well", Vector3(12.5, 0, 5.7), 2.3)
	sprite("apple_tree", Vector3(14.6, 0, 5.4), 3.8)
	for plot in [
		Vector2(11, 9),
		Vector2(6, 9),
		Vector2(16, 9),
		Vector2(1, 9),
		Vector2(21, 9),
		Vector2(11, 13),
		Vector2(6, 13),
		Vector2(16, 13)
	]:
		var crops := rng.randi_range(5, 12)
		for i in crops:
			var stage := rng.randi_range(0, 3)
			var size: float = [0.35, 0.75, 0.85, 0.95][stage]
			sprite(
				"pumpkin_%d" % stage,
				Vector3(plot.x + i % 4 + 0.5, 0, plot.y + i / 4 + 0.8),
				size * 1.05
			)
	_forest()
	for at in [
		Vector3(8.2, 0, 5.8),
		Vector3(9.4, 0, 5.1),
		Vector3(20.3, 0, 3.6),
		Vector3(21.5, 0, 4.4),
		Vector3(19.1, 0, 4.8)
	]:
		sprite("chicken", at, 0.62)
	for at in [Vector3(22.5, 0, 3.2), Vector3(24.6, 0, 4.6)]:
		sprite("cow", at, 0.95)
	for v in [
		[Vector3(2.0, 0, 6.0), "a"],
		[Vector3(7.0, 0, 6.0), "b"],
		[Vector3(13.6, 0, 6.7), "a"],
		[Vector3(18.5, 0, 7.4), "b"]
	]:
		sprite("villager_" + v[1], v[0], 0.85)


func _forest() -> void:
	for z in range(-7, 1):
		for x in range(-8, 34):
			if x >= 15 and x <= 18 and z > -7:
				continue
			if rng.randf() < 0.7:
				_tree(
					Vector3(x + rng.randf_range(-0.4, 0.4), 0, z + 0.5 + rng.randf_range(-0.3, 0.3))
				)
	for z in range(1, 20):
		for x in [-8, -7, -6, -5, -4, -3, -2, -1, 27, 28, 29, 30, 31, 32, 33]:
			if z >= 6 and z <= 8:
				continue
			if rng.randf() < (0.55 if x in [-1, 27] else 0.8):
				_tree(Vector3(x + rng.randf_range(-0.4, 0.4), 0, z + rng.randf_range(-0.3, 0.3)))
	for x in range(-8, 34):
		if rng.randf() < 0.5:
			_tree(Vector3(x + rng.randf_range(-0.4, 0.4), 0, 17.6 + rng.randf_range(0.0, 1.5)))


func _tree(at: Vector3) -> void:
	var autumn := rng.randf() < 0.3
	sprite("tree_autumn" if autumn else "tree_green", at, rng.randf_range(2.8, 4.2))


func _dust() -> void:
	var p := CPUParticles3D.new()
	p.amount = 160
	p.lifetime = 12.0
	p.preprocess = 12.0
	p.emission_shape = CPUParticles3D.EMISSION_SHAPE_BOX
	p.emission_box_extents = Vector3(16, 3, 11)
	p.position = CENTRE + Vector3(0, 3, 0)
	p.gravity = Vector3(0, 0.02, 0)
	p.initial_velocity_min = 0.05
	p.initial_velocity_max = 0.2
	p.direction = Vector3(-1, 0.2, 0.3)
	p.spread = 60
	var q := QuadMesh.new()
	q.size = Vector2(0.06, 0.06)
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
	p.amount = 24
	p.lifetime = 4.0
	p.preprocess = 4.0
	p.position = at
	p.direction = Vector3(0.3, 1, 0)
	p.spread = 12
	p.gravity = Vector3(0.15, 0.25, 0)
	p.initial_velocity_min = 0.3
	p.initial_velocity_max = 0.5
	p.scale_amount_min = 0.5
	p.scale_amount_max = 0.8
	var curve := Curve.new()
	curve.add_point(Vector2(0, 0.4))
	curve.add_point(Vector2(1, 1.6))
	p.scale_amount_curve = curve
	var fade := Gradient.new()
	fade.set_color(0, Color(0.92, 0.9, 0.88, 0.7))
	fade.set_color(1, Color(0.92, 0.9, 0.88, 0.0))
	p.color_ramp = fade
	var q := QuadMesh.new()
	q.size = Vector2(0.45, 0.45)
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.billboard_mode = BaseMaterial3D.BILLBOARD_ENABLED
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.vertex_color_use_as_albedo = true
	var g := GradientTexture2D.new()
	var soft := Gradient.new()
	soft.set_color(0, Color(1, 1, 1, 1))
	soft.set_color(1, Color(1, 1, 1, 0))
	g.gradient = soft
	g.fill = GradientTexture2D.FILL_RADIAL
	g.fill_from = Vector2(0.5, 0.5)
	g.fill_to = Vector2(0.5, 0.0)
	m.albedo_texture = g
	q.material = m
	p.mesh = q
	add_child(p)
