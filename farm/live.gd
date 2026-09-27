extends Node3D

## The live farm: every line in is one whole farm as the app's reducer holds it
## (`app/.../farm/Snapshot.kt`), and this moves the brick world to match. Kotlin says which slot
## each thing has; the places below say where each slot stands (docs/adr/0003-godot-farm.md).

signal settled

## A farmer's walk, in world units a second: about three tiles.
const WALK := 2.6
## How high a walking farmer's step lifts it, in world units.
const STEP := 0.06
const STEPS_A_SECOND := 5.0

## Where a farmer with nothing out waits, in bricks, first heard first: the farmhouse yard, then
## the verge by the paddock. Past the last one farmers share it.
const HOMES: Array[Vector2i] = [
	Vector2i(9, 27),
	Vector2i(29, 27),
	Vector2i(5, 27),
	Vector2i(33, 27),
	Vector2i(13, 27),
	Vector2i(37, 27),
	Vector2i(60, 33),
	Vector2i(64, 33),
	Vector2i(68, 33),
	Vector2i(72, 33),
	Vector2i(76, 33),
	Vector2i(80, 33)
]
## The line at the well: nearest first, either side of it along the lane, then the far edge.
const QUEUE: Array[Vector2i] = [
	Vector2i(48, 30),
	Vector2i(40, 30),
	Vector2i(52, 30),
	Vector2i(36, 30),
	Vector2i(56, 30),
	Vector2i(32, 30),
	Vector2i(60, 30),
	Vector2i(28, 30),
	Vector2i(48, 32),
	Vector2i(40, 32),
	Vector2i(52, 32),
	Vector2i(36, 32),
	Vector2i(56, 32),
	Vector2i(32, 32),
	Vector2i(60, 32),
	Vector2i(28, 32)
]
## One crop kind per bed, by the bed's slot, so neighbouring directories read apart.
const KINDS: Array[String] = ["turnip", "pumpkin", "tomato", "carrot"]
## Crops a bed has room for: three rows of three.
const CELLS := 9
const KEY := (
	"farmer = a session   ·   bed = a directory   ·   crop = a file   ·   "
	+ "well = a model call   ·   bin = what it cost"
)
## How long the app may say nothing, heartbeats included, before the window closes itself.
const SILENCE_MS := 15000
## How near a click has to land to a farmer or a crop, in pixels.
const REACH := 40.0

var world: Node3D

var _farmers := {}
var _crops: Array = []
var _crop_layer: MultiMeshInstance3D
var _fields_seen := ""
var _labels: Array[Label3D] = []
var _bin_label: Label3D
var _badge: Label
var _rain: CPUParticles3D
var _day := {}
var _weather := "CLEAR"
var _season := "AUTUMN"
var _spills := 0
var _flash := 0.0

var _inbox: Array[String] = []
var _lock := Mutex.new()
var _reader := Thread.new()
var _reading := true
var _from_app := false
var _last_heard := 0
var _drawn := false
var _feed: Array[String] = []
var _feed_every := 0.5
var _feed_clock := 0.0
var _shot := false


func _ready() -> void:
	_day = {
		"beam": world.beam.light_energy,
		"beam_colour": world.beam.light_color,
		"ambient": world.environment.ambient_light_energy,
		"fill": world.fill.light_energy,
	}
	_hud()
	_weather_fx()
	var args := OS.get_cmdline_user_args()
	_shot = "--shot" in args
	for i in args.size():
		if args[i] == "--from-app":
			_from_app = true
		elif args[i] == "--feed" and i + 1 < args.size():
			_feed.assign(FileAccess.get_file_as_string(args[i + 1]).split("\n", false))
		elif args[i] == "--every" and i + 1 < args.size():
			_feed_every = float(args[i + 1])
		elif args[i] == "--shot-after" and i + 1 < args.size():
			_shoot_later(float(args[i + 1]))
	if _feed.is_empty() and _from_app:
		_last_heard = Time.get_ticks_msec()
		_reader.start(_read_stdin)
		var watch := Timer.new()
		watch.wait_time = 1.0
		watch.autostart = true
		watch.timeout.connect(_check_app)
		add_child(watch)
	if _feed.is_empty() and _shot:
		_shoot_later(2.0)


func _exit_tree() -> void:
	_reading = false
	if _reader.is_started():
		_reader.wait_to_finish()


## Reads the app's lines off stdin, a byte at a time: `read_buffer_from_stdin(n)` waits for all n
## bytes or the end of the pipe, so asking for more than one would hold back every farm and every
## heartbeat until enough had queued behind it. An empty line is the app's heartbeat: a closed pipe
## reads the same as a quiet one, so silence is how [method _check_app] knows the app has gone.
func _read_stdin() -> void:
	var pending := PackedByteArray()
	while _reading:
		var byte := OS.read_buffer_from_stdin(1)
		if byte.is_empty():
			OS.delay_msec(15)
			continue
		if byte[0] != 10:
			pending.append_array(byte)
			continue
		var line := pending.get_string_from_utf8().strip_edges()
		pending.clear()
		_lock.lock()
		_last_heard = Time.get_ticks_msec()
		if not line.is_empty():
			_inbox.append(line)
		_lock.unlock()


## Three heartbeats missed: the app has gone, and a farm nobody feeds should not stay open.
func _check_app() -> void:
	_lock.lock()
	var quiet := Time.get_ticks_msec() - _last_heard
	_lock.unlock()
	if quiet > SILENCE_MS:
		get_tree().quit()


func _process(delta: float) -> void:
	if not _drawn:
		# The first frames of a software renderer can take longer than the app's silence allowance;
		# the count starts once the farm is on screen.
		_drawn = true
		_lock.lock()
		_last_heard = Time.get_ticks_msec()
		_lock.unlock()
	_play_feed(delta)
	_lock.lock()
	var latest := "" if _inbox.is_empty() else _inbox[_inbox.size() - 1]
	_inbox.clear()
	_lock.unlock()
	if not latest.is_empty():
		var farm = JSON.parse_string(latest)
		if farm is Dictionary:
			_apply(farm)
	_walk(delta)
	if _flash > 0.0:
		_flash = maxf(0.0, _flash - delta * 3.0)
		world.environment.adjustment_brightness = 1.0 + _flash * 1.5


func _play_feed(delta: float) -> void:
	if _feed.is_empty():
		return
	_feed_clock += delta
	if _feed_clock < _feed_every:
		return
	_feed_clock = 0.0
	_lock.lock()
	_inbox.append(_feed.pop_front())
	_lock.unlock()
	if _feed.is_empty() and _shot:
		_shoot_later(4.0)


func _shoot_later(seconds: float) -> void:
	await get_tree().create_timer(seconds).timeout
	settled.emit()
	await RenderingServer.frame_post_draw
	# An exported farm is packed and cannot write into itself; it keeps its still in user://.
	var still := "res://shot.png" if OS.has_feature("editor") else "user://shot.png"
	get_viewport().get_texture().get_image().save_png(still)
	printerr("still: ", ProjectSettings.globalize_path(still))
	get_tree().quit()


func _apply(farm: Dictionary) -> void:
	_apply_fields(farm.get("fields", []), farm.get("labelsHidden", true))
	_apply_farmers(farm.get("villagers", []))
	_apply_sky(farm)
	var bin: Dictionary = farm.get("bin", {})
	var line := "%d shipped · $%.2f" % [int(bin.get("produce", 0)), float(bin.get("ledger", 0.0))]
	if int(bin.get("unpriced", 0)) > 0:
		line += " · %d unpriced" % int(bin.get("unpriced", 0))
	_bin_label.text = line
	_badge.visible = not farm.get("labelsHidden", true)


# --- fields ---------------------------------------------------------------------------------


func _apply_fields(fields: Array, hidden: bool) -> void:
	var seen := JSON.stringify([fields, hidden])
	if seen == _fields_seen:
		return
	_fields_seen = seen
	for label in _labels:
		label.queue_free()
	_labels.clear()
	_crops.clear()
	var saved: Dictionary = world.bricks
	world.bricks = {}
	var beds: Array[Vector2i] = world.BEDS
	for slot in mini(fields.size(), beds.size()):
		var field: Dictionary = fields[slot]
		var bed := beds[slot]
		var crops: Array = field.get("crops", [])
		for cell in mini(crops.size(), CELLS):
			var crop: Dictionary = crops[cell]
			var x := bed.x + 1 + (cell % 3) * 3
			var z := bed.y + 1 + (cell / 3) * 2
			world.crop(KINDS[slot % KINDS.size()], int(crop.get("growth", 0)), x, z)
			_crops.append([str(crop.get("path", "")), _at(Vector2i(x + 1, z))])
		if not hidden:
			var more := crops.size() - CELLS
			var name: String = field.get("label", "")
			_label(name + (" +%d" % more if more > 0 else ""), _at(bed) + Vector3(1.4, 0.35, 2.35))
	if fields.size() > beds.size():
		var note := "+%d fields not shown" % (fields.size() - beds.size())
		_label(note, _at(Vector2i(20, 57)) + Vector3(0, 0.3, 0))
	var grown: Dictionary = world.bricks
	world.bricks = saved
	if _crop_layer:
		_crop_layer.queue_free()
		_crop_layer = null
	var items := []
	for p in grown:
		items.append(
			[Transform3D(Basis(), Vector3(p.x * world.B, p.y * world.H, p.z * world.B)), grown[p]]
		)
	if not items.is_empty():
		_crop_layer = world.instances(world.brick_mesh, items, world.material)


# --- farmers --------------------------------------------------------------------------------


func _apply_farmers(villagers: Array) -> void:
	var here := {}
	for v in villagers:
		var id := str(v.get("id", ""))
		here[id] = true
		if not _farmers.has(id):
			_farmers[id] = _new_farmer(v)
		var f: Dictionary = _farmers[id]
		f["data"] = v
	for id in _farmers.keys():
		if not here.has(id):
			_farmers[id]["node"].queue_free()
			_farmers.erase(id)
	# helpers stand beside the one they help, in the order they were heard
	var abreast := {}
	for v in villagers:
		var parent = v.get("parent")
		if parent != null and _farmers.has(str(parent)):
			var nth: int = abreast.get(parent, 0)
			abreast[parent] = nth + 1
			_farmers[str(v.get("id"))]["nth"] = nth


func _new_farmer(v: Dictionary) -> Dictionary:
	var node := Node3D.new()
	var body := MultiMeshInstance3D.new()
	body.multimesh = world.farmer_look(int(v.get("look", 0)))
	var mat := StandardMaterial3D.new()
	mat.vertex_color_use_as_albedo = true
	mat.roughness = 0.35
	body.material_override = mat
	body.rotation.y = deg_to_rad(world.CAMERA_YAW)
	node.add_child(body)
	var name := _tag(str(v.get("name", "")), 24)
	name.position = Vector3(0, world.FARMER_HEIGHT + 0.25, 0)
	node.add_child(name)
	var mood := _tag("", 34)
	mood.position = Vector3(0, world.FARMER_HEIGHT + 0.55, 0)
	node.add_child(mood)
	add_child(node)
	var f := {"node": node, "body": body, "mood": mood, "data": v, "nth": 0, "moving": false}
	node.position = _target(f)
	return f


## Where a farmer is headed: its place in the queue while a turn is out or it is resting off a
## rate limit, and home otherwise; a helper's home is beside whoever it helps, wherever they are.
func _target(f: Dictionary) -> Vector3:
	var v: Dictionary = f["data"]
	var activity := str(v.get("activity", "IDLE"))
	if activity in ["WALKING_TO_WELL", "RESTING"] and v.get("queue") != null:
		return _at(QUEUE[mini(int(v["queue"]), QUEUE.size() - 1)])
	var parent = v.get("parent")
	if parent != null and _farmers.has(str(parent)) and str(parent) != str(v.get("id")):
		var at: Vector3 = _farmers[str(parent)]["node"].position
		return at + Vector3(0.55 * (int(f["nth"]) + 1), 0, 0.3)
	if v.get("home") != null:
		return _at(HOMES[mini(int(v["home"]), HOMES.size() - 1)])
	return _at(HOMES[0])


func _walk(delta: float) -> void:
	var t := Time.get_ticks_msec() / 1000.0
	for id in _farmers:
		var f: Dictionary = _farmers[id]
		var node: Node3D = f["node"]
		var goal := _target(f)
		var flat := Vector3(node.position.x, goal.y, node.position.z)
		var gap := goal - flat
		var moving := gap.length() > 0.02
		if moving:
			var step := minf(gap.length(), WALK * delta)
			flat += gap.normalized() * step
		var lift := absf(sin(t * STEPS_A_SECOND * PI)) * STEP if moving else 0.0
		node.position = Vector3(flat.x, goal.y + lift, flat.z)
		var activity := str(f["data"].get("activity", "IDLE"))
		var mood: Label3D = f["mood"]
		if moving:
			mood.text = ""
		elif activity == "WALKING_TO_WELL":
			mood.text = "…"
		elif activity == "RESTING":
			mood.text = "zzz"
		else:
			mood.text = ""
		f["moving"] = moving


# --- sky ------------------------------------------------------------------------------------


## The light over the diorama from the farm's night and weather, and the trees from its season.
##
## ponytail: the season dresses the trees, the ground, the roofs and the flowers, and leaves the
## sea and the weather alone, so winter rain still falls as rain. Upgrade: snowflakes for winter
## rain and ice at the shore, if a winter farm ever needs to look colder than its ground.
func _apply_sky(farm: Dictionary) -> void:
	var season := str(farm.get("season", "AUTUMN"))
	if season != _season:
		_season = season
		world.paint_season(season)
	var night: bool = farm.get("night", false)
	var weather := str(farm.get("weather", "CLEAR"))
	var dim := 1.0
	if weather in ["STORM", "LIGHTNING"]:
		dim = 0.55
	elif weather == "RAIN":
		dim = 0.8
	if night:
		world.beam.light_color = Color(0.55, 0.65, 1.0)
		world.beam.light_energy = _day["beam"] * 0.5 * dim
		world.environment.ambient_light_energy = _day["ambient"] * 0.75
		world.fill.light_energy = _day["fill"] * 0.4
	else:
		world.beam.light_color = _day["beam_colour"]
		world.beam.light_energy = _day["beam"] * dim
		world.environment.ambient_light_energy = _day["ambient"] * (0.6 + 0.4 * dim)
		world.fill.light_energy = _day["fill"]
	for lamp in world.lamps:
		lamp.light_energy = 4.0 if night else 1.2
	_rain.emitting = weather != "CLEAR"
	_rain.amount = 260 if weather == "RAIN" else 600
	var spills := 0
	for v in farm.get("villagers", []):
		spills += int(v.get("spills", 0))
	var struck := weather == "LIGHTNING" and _weather != "LIGHTNING"
	if struck or spills > _spills:
		_flash = 1.0
	_weather = weather
	_spills = spills


func _weather_fx() -> void:
	_rain = CPUParticles3D.new()
	_rain.emitting = false
	_rain.amount = 260
	_rain.lifetime = 1.2
	_rain.emission_shape = CPUParticles3D.EMISSION_SHAPE_BOX
	_rain.emission_box_extents = Vector3(14, 0.5, 10)
	_rain.position = Vector3(world.W * world.B / 2, 9, world.D * world.B / 2)
	_rain.direction = Vector3(0.15, -1, 0)
	_rain.spread = 3
	_rain.gravity = Vector3(0, -18, 0)
	_rain.initial_velocity_min = 6
	_rain.initial_velocity_max = 8
	var streak := BoxMesh.new()
	streak.size = Vector3(0.02, 0.35, 0.02)
	var m := StandardMaterial3D.new()
	m.albedo_color = Color(0.75, 0.85, 1.0, 0.55)
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	streak.material = m
	_rain.mesh = streak
	add_child(_rain)


# --- words ----------------------------------------------------------------------------------


func _hud() -> void:
	var layer := CanvasLayer.new()
	add_child(layer)
	var key := Label.new()
	key.text = KEY
	key.add_theme_color_override("font_color", Color(1, 0.95, 0.85, 0.85))
	key.add_theme_color_override("font_outline_color", Color(0, 0, 0, 0.8))
	key.add_theme_constant_override("outline_size", 6)
	key.add_theme_font_size_override("font_size", 15)
	key.anchor_top = 1.0
	key.anchor_bottom = 1.0
	key.offset_left = 16
	key.offset_top = -34
	layer.add_child(key)
	_badge = Label.new()
	_badge.text = " PATHS VISIBLE "
	_badge.add_theme_color_override("font_color", Color(0.1, 0.1, 0.1))
	_badge.add_theme_font_size_override("font_size", 16)
	var plate := StyleBoxFlat.new()
	plate.bg_color = Color(1.0, 0.77, 0.0)
	_badge.add_theme_stylebox_override("normal", plate)
	_badge.anchor_left = 1.0
	_badge.anchor_right = 1.0
	_badge.offset_left = -150
	_badge.offset_top = 12
	_badge.visible = false
	layer.add_child(_badge)
	_bin_label = _tag("0 shipped · $0.00", 26)
	_bin_label.position = _at(Vector2i(29, 27)) + Vector3(0, 1.1, 0)
	add_child(_bin_label)


func _label(text: String, at: Vector3) -> void:
	var label := _tag(text, 22)
	label.position = at
	add_child(label)
	_labels.append(label)


func _tag(text: String, size: int) -> Label3D:
	var label := Label3D.new()
	label.text = text
	label.font_size = size
	label.pixel_size = 0.011
	label.outline_size = 8
	label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	label.no_depth_test = true
	label.modulate = Color(1, 0.97, 0.9)
	return label


# --- clicks ---------------------------------------------------------------------------------


func _unhandled_input(event: InputEvent) -> void:
	var click := event as InputEventMouseButton
	if click == null or not click.pressed or click.button_index != MOUSE_BUTTON_LEFT:
		return
	var camera: Camera3D = world.camera
	var best := REACH
	var said := ""
	for id in _farmers:
		var node: Node3D = _farmers[id]["node"]
		var chest := node.position + Vector3(0, world.FARMER_HEIGHT * 0.5, 0)
		var d := camera.unproject_position(chest).distance_to(click.position)
		if d < best:
			best = d
			said = JSON.stringify({"villager": id})
	for crop in _crops:
		var d := camera.unproject_position(crop[1]).distance_to(click.position)
		if d < best:
			best = d
			said = JSON.stringify({"crop": crop[0]})
	if not said.is_empty():
		print(said)


## A brick place on the ground, as a point in the world.
func _at(p: Vector2i) -> Vector3:
	return Vector3((p.x + 0.5) * world.B, (world.GROUND + 1) * world.H, (p.y + 0.5) * world.B)
