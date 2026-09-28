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
## How long a farmer works at each crop its trip planted or grew, in seconds.
const TENDING := 1.4
## How far a farmer at home drifts about its spot while it waits, in world units.
const DRIFT := Vector2(0.5, 0.25)

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
## The homes each farmhouse extension adds, six a wing: first along the lane past the well, then
## at its west end.
const WING_HOMES: Array[Vector2i] = [
	Vector2i(42, 27),
	Vector2i(50, 27),
	Vector2i(54, 27),
	Vector2i(58, 27),
	Vector2i(62, 27),
	Vector2i(66, 27),
	Vector2i(8, 31),
	Vector2i(12, 31),
	Vector2i(16, 31),
	Vector2i(20, 31),
	Vector2i(24, 31),
	Vector2i(84, 31)
]
const HOMES_A_WING := 6
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
## A winter day's low sun, and the haze it lights: paler and cooler than the rest of the year's.
const WINTER_SUN := Color(0.86, 0.92, 1.0)
const WINTER_HAZE := Color(0.88, 0.93, 1.0)

var world: Node3D

var _farmers := {}
var _crops: Array = []
## Where a farmer stands to tend each drawn crop, by path: in the bed, just in front of it.
var _tending_spots := {}
var _rng := RandomNumberGenerator.new()
var _crop_layer: MultiMeshInstance3D
var _fields_seen := ""
var _labels: Array[Label3D] = []
var _bin_label: Label3D
var _badge: Label
## The farm's level, its coins, and how far it is to the next level; see [method _apply_purse].
var _level: Label
var _progress: ProgressBar
## The "For sale" sign standing in the sea where the next plot will rise, and its price tag.
var _sign: Node3D
## Where farmers with nothing out wait: [constant HOMES] and the homes of each extension built.
var _homes: Array[Vector2i] = HOMES.duplicate()
## The dog and the cat; see pets.gd.
var _pets: Node3D
## The animal shop's board, and the coop and barn as upgraded; see shop.gd.
var _shop: Node3D
var _sign_tag: Label3D
var _rain: CPUParticles3D
var _snow: CPUParticles3D
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
		"haze": world.environment.volumetric_fog_albedo,
		"saturation": world.environment.adjustment_saturation,
	}
	_rng.seed = 5
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
	var land: Dictionary = farm.get("land", {})
	var herd: Dictionary = farm.get("herd", {})
	var barn := mini(int(herd.get("barn", 0)), world.Pasture.MOST_STRETCHES)
	world.grow(int(land.get("plots", 0)), barn)
	_apply_sign(land, farm.get("purse", {}))
	var buildings: Dictionary = farm.get("buildings", {})
	_shop.apply(herd, buildings)
	world.life.herd(herd)
	_homes = HOMES.duplicate()
	_homes.append_array(WING_HOMES.slice(0, int(buildings.get("house", 0)) * HOMES_A_WING))
	_pets.apply(farm.get("pets", []), farm.get("villagers", []))
	_apply_fields(farm.get("fields", []), farm.get("labelsHidden", true))
	_apply_farmers(farm.get("villagers", []))
	_apply_sky(farm)
	var bin: Dictionary = farm.get("bin", {})
	var line := "%d shipped · $%.2f" % [int(bin.get("produce", 0)), float(bin.get("ledger", 0.0))]
	if int(bin.get("unpriced", 0)) > 0:
		line += " · %d unpriced" % int(bin.get("unpriced", 0))
	_bin_label.text = line
	_badge.visible = not farm.get("labelsHidden", true)
	_apply_purse(farm.get("purse", {}))


## The sign moved to where the next plot will rise, in the sea off the shore it will join, with its
## price; gold when the purse can pay for it, and grey until then.
func _apply_sign(land: Dictionary, purse: Dictionary) -> void:
	var price := int(land.get("price", 20))
	var corner: Vector2i = world.Land.corner(int(land.get("plots", 0)))
	_sign.position = (
		_at(corner + Vector2i(world.Land.PLOT_W / 2, 5)) - Vector3(0, world.GROUND * world.H, 0)
	)
	_sign_tag.text = "FOR SALE\n%d coins" % price
	var affordable := int(purse.get("coins", 0)) >= price
	_sign_tag.modulate = Color(1, 0.85, 0.3) if affordable else Color(0.75, 0.75, 0.72)


## What the agents' work has earned: the level, the coins to spend, and the bar to the next level,
## which runs from the lifetime coins this level began at to those the next one begins at.
func _apply_purse(purse: Dictionary) -> void:
	var earned := int(purse.get("earned", 0))
	var floor_at := int(purse.get("floor", 0))
	var next_at := int(purse.get("next", 10))
	_level.text = (
		"Level %d   ·   %d coins" % [int(purse.get("level", 1)), int(purse.get("coins", 0))]
	)
	_progress.max_value = maxi(next_at - floor_at, 1)
	_progress.value = earned - floor_at
	_progress.tooltip_text = "%d of %d to the next level" % [earned - floor_at, next_at - floor_at]


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
	_tending_spots.clear()
	var saved: Dictionary = world.bricks
	world.bricks = {}
	var beds: Array[Vector2i] = world.beds()
	for slot in mini(fields.size(), beds.size()):
		var field: Dictionary = fields[slot]
		var bed := beds[slot]
		var crops: Array = field.get("crops", [])
		for cell in mini(crops.size(), CELLS):
			var crop: Dictionary = crops[cell]
			var x := bed.x + 1 + (cell % 3) * 3
			var z := bed.y + 1 + (cell / 3) * 2
			if crop.get("dormant", false):
				# winter: the open bed's crop sleeps under a mound of snow until spring
				world.box(x, world.GROUND + 2, z, 3, 1, 2, world.SNOW)
				world.put(x + 1, world.GROUND + 3, z, world.SNOW)
				world.put(x + 1, world.GROUND + 3, z + 1, world.LEAF)
			else:
				world.crop(str(crop.get("kind", "carrot")), int(crop.get("growth", 0)), x, z)
			var path := str(crop.get("path", ""))
			_crops.append([path, _at(Vector2i(x + 1, z))])
			_tending_spots[path] = _at(Vector2i(x + 1, z + 2)) + Vector3(0.1, world.H, 0)
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
		_heard_tended(f, v)
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
	var f := {
		"node": node,
		"body": body,
		"mood": mood,
		"data": v,
		"nth": 0,
		"moving": false,
		"turns": 0,
		"rounds": [],
		"work": 0.0,
		"drift": Vector3.ZERO,
		"wait": 0.0,
	}
	node.position = _target(f)
	return f


## The crops a turn planted or grew join the farmer's rounds for its way home, each once. A turn is
## new when the count of them moves, which is what tells two turns that worked the same files apart.
func _heard_tended(f: Dictionary, v: Dictionary) -> void:
	var turns := int(v.get("turns", 0))
	if turns == f["turns"]:
		return
	f["turns"] = turns
	var rounds: Array = f["rounds"]
	for path in v.get("tended", []):
		if not rounds.has(path):
			rounds.append(path)


## Whether the farmer is off at the well, where no round can take it.
func _at_well(f: Dictionary) -> bool:
	var activity := str(f["data"].get("activity", "IDLE"))
	return activity in ["WALKING_TO_WELL", "RESTING"] and f["data"].get("queue") != null


## The crop the farmer is on its way to, or null once its rounds are done. A crop no bed shows,
## past the eighth field or the ninth crop, is skipped: there is nowhere to stand.
func _next_round(f: Dictionary) -> Variant:
	var rounds: Array = f["rounds"]
	while not rounds.is_empty() and not _tending_spots.has(rounds[0]):
		rounds.pop_front()
	return null if rounds.is_empty() else _tending_spots[rounds[0]]


## Where a farmer is headed: its place in the queue while a turn is out or it is resting off a
## rate limit; then each crop its trip planted or grew, in turn; and home otherwise, drifting
## about there. A helper's home is beside whoever it helps, wherever they are.
func _target(f: Dictionary) -> Vector3:
	var v: Dictionary = f["data"]
	if _at_well(f):
		return _at(QUEUE[mini(int(v["queue"]), QUEUE.size() - 1)])
	var crop = _next_round(f)
	if crop != null:
		return crop
	var parent = v.get("parent")
	if parent != null and _farmers.has(str(parent)) and str(parent) != str(v.get("id")):
		var at: Vector3 = _farmers[str(parent)]["node"].position
		return at + Vector3(0.55 * (int(f["nth"]) + 1), 0, 0.3)
	var home := _homes[0]
	if v.get("home") != null:
		home = _homes[mini(int(v["home"]), _homes.size() - 1)]
	return _at(home) + f["drift"]


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
		var tending := not moving and not _at_well(f) and _next_round(f) != null
		_tend(f, delta, tending)
		if not moving and not tending and not _at_well(f):
			_idle(f, delta)
		var activity := str(f["data"].get("activity", "IDLE"))
		var mood: Label3D = f["mood"]
		if moving:
			mood.text = ""
		elif tending:
			mood.text = "♪"
		elif activity == "WALKING_TO_WELL":
			mood.text = "…"
		elif activity == "RESTING":
			mood.text = "zzz"
		else:
			mood.text = ""
		f["moving"] = moving


## A farmer at a crop of its rounds bends to it, hoe and watering can, for [constant TENDING]
## seconds, and then moves on to the next.
func _tend(f: Dictionary, delta: float, tending: bool) -> void:
	var body: MultiMeshInstance3D = f["body"]
	if not tending:
		f["work"] = 0.0
		body.rotation.x = lerpf(body.rotation.x, 0.0, minf(1.0, delta * 8.0))
		return
	f["work"] += delta
	body.rotation.x = -absf(sin(float(f["work"]) * 7.0)) * 0.3
	if f["work"] >= TENDING:
		f["work"] = 0.0
		var rounds: Array = f["rounds"]
		rounds.pop_front()


## A farmer with nothing out does not stand like a post: every few seconds it shifts to another
## spot near its home.
func _idle(f: Dictionary, delta: float) -> void:
	f["wait"] -= delta
	if f["wait"] > 0.0:
		return
	f["wait"] = _rng.randf_range(2.5, 6.0)
	f["drift"] = Vector3(
		_rng.randf_range(-DRIFT.x, DRIFT.x), 0, _rng.randf_range(-DRIFT.y, DRIFT.y)
	)


# --- sky ------------------------------------------------------------------------------------


## The light over the diorama from the farm's night, weather and season, and the island dressed
## for the season: a winter day has a paler, cooler sun, a little weaker so the snow does not
## glare, in a bluer haze and with the colours a touch greyer.
func _apply_sky(farm: Dictionary) -> void:
	var season := str(farm.get("season", "AUTUMN"))
	if season != _season:
		_season = season
		world.paint_season(season)
	var night: bool = farm.get("night", false)
	var weather := str(farm.get("weather", "CLEAR"))
	var winter := season == "WINTER"
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
		world.beam.light_color = WINTER_SUN if winter else _day["beam_colour"]
		world.beam.light_energy = _day["beam"] * dim * (0.8 if winter else 1.0)
		world.environment.ambient_light_energy = _day["ambient"] * (0.6 + 0.4 * dim)
		world.fill.light_energy = _day["fill"]
	world.environment.volumetric_fog_albedo = WINTER_HAZE if winter else _day["haze"]
	world.environment.adjustment_saturation = _day["saturation"] * (0.9 if winter else 1.0)
	for lamp in world.lamps:
		lamp.light_energy = 4.0 if night else 1.2
	_rain.emitting = weather != "CLEAR" and not winter
	_rain.amount = 260 if weather == "RAIN" else 600
	_snow.emitting = weather != "CLEAR" and winter
	_snow.amount = 420 if weather == "RAIN" else 900
	# a storm drives the snow sideways
	_snow.direction = Vector3(0.2, -1, 0) if weather == "RAIN" else Vector3(1.2, -1, 0.3)
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
	_snowfall()


## Winter's rain: slow flakes that are tiny white bricks, tumbling as they fall. It starts with the
## air already full, rather than a first flake taking seconds to reach the ground.
func _snowfall() -> void:
	_snow = CPUParticles3D.new()
	_snow.emitting = false
	_snow.amount = 420
	_snow.lifetime = 7.0
	_snow.preprocess = 7.0
	_snow.emission_shape = CPUParticles3D.EMISSION_SHAPE_BOX
	_snow.emission_box_extents = Vector3(15, 0.5, 11)
	_snow.position = Vector3(world.W * world.B / 2, 9, world.D * world.B / 2)
	_snow.spread = 25
	_snow.gravity = Vector3(0, -0.4, 0)
	_snow.initial_velocity_min = 1.0
	_snow.initial_velocity_max = 1.6
	_snow.particle_flag_rotate_y = true
	_snow.angular_velocity_min = -180
	_snow.angular_velocity_max = 180
	var flake := BoxMesh.new()
	flake.size = Vector3(0.05, 0.04, 0.05)
	var m := StandardMaterial3D.new()
	m.albedo_color = Color(0.97, 0.98, 1.0)
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	flake.material = m
	_snow.mesh = flake
	add_child(_snow)


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
	_hud_purse(layer)
	_sign = _sign_post()
	add_child(_sign)
	_shop = preload("res://shop.gd").new()
	_shop.world = world
	add_child(_shop)
	_pets = preload("res://pets.gd").new()
	_pets.world = world
	_pets.live = self
	add_child(_pets)
	_bin_label = _tag("0 shipped · $0.00", 26)
	_bin_label.position = _at(Vector2i(29, 27)) + Vector3(0, 1.1, 0)
	add_child(_bin_label)


## The level badge in the top-left corner: a plate with the level and coins, and a bar under it.
func _hud_purse(layer: CanvasLayer) -> void:
	var plate := StyleBoxFlat.new()
	plate.bg_color = Color(0.2, 0.12, 0.06, 0.8)
	plate.set_corner_radius_all(6)
	plate.set_content_margin_all(8)
	var box := VBoxContainer.new()
	box.add_theme_constant_override("separation", 4)
	var panel := PanelContainer.new()
	panel.position = Vector2(16, 12)
	panel.add_theme_stylebox_override("panel", plate)
	panel.add_child(box)
	layer.add_child(panel)
	_level = Label.new()
	_level.text = "Level 1   ·   0 coins"
	_level.add_theme_color_override("font_color", Color(1, 0.9, 0.55))
	_level.add_theme_font_size_override("font_size", 18)
	box.add_child(_level)
	_progress = ProgressBar.new()
	_progress.custom_minimum_size = Vector2(200, 10)
	_progress.show_percentage = false
	var track := StyleBoxFlat.new()
	track.bg_color = Color(0, 0, 0, 0.45)
	track.set_corner_radius_all(4)
	var fill := StyleBoxFlat.new()
	fill.bg_color = Color(0.55, 0.8, 0.25)
	fill.set_corner_radius_all(4)
	_progress.add_theme_stylebox_override("background", track)
	_progress.add_theme_stylebox_override("fill", fill)
	box.add_child(_progress)


## A signpost of bricks standing in the sea, a board across its top, and its price tag above.
func _sign_post() -> Node3D:
	var items := []
	for y in range(1, 9):
		items.append([Vector3i(0, y, 0), world.WOOD_DARK])
	for x in range(-3, 4):
		for y in range(6, 9):
			var edge := x in [-3, 3] or y in [6, 8]
			items.append([Vector3i(x, y, 1), world.WOOD_DARK if edge else world.WOOD])
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.use_colors = true
	mm.mesh = world.brick_mesh
	mm.instance_count = items.size()
	for i in items.size():
		var p: Vector3i = items[i][0]
		mm.set_instance_transform(
			i, Transform3D(Basis(), Vector3(p.x * world.B, p.y * world.H, p.z * world.B))
		)
		mm.set_instance_color(i, items[i][1])
	var body := MultiMeshInstance3D.new()
	body.multimesh = mm
	body.material_override = world.material
	var node := Node3D.new()
	node.add_child(body)
	_sign_tag = _tag("FOR SALE", 24)
	_sign_tag.position = Vector3(0, 11 * world.H, 0)
	node.add_child(_sign_tag)
	return node


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
	var board := _sign.position + Vector3(0, 7 * world.H, 0)
	if camera.unproject_position(board).distance_to(click.position) < best:
		best = camera.unproject_position(board).distance_to(click.position)
		said = JSON.stringify({"buy": "plot"})
	var item: String = _shop.hit(camera, click.position, best)
	if not item.is_empty():
		said = JSON.stringify({"buy": item})
	if not said.is_empty():
		print(said)


## Where farmer [param id] stands, or null when there is no such farmer; the pets watch them.
func farmer_at(id: String) -> Variant:
	if not _farmers.has(id):
		return null
	var node: Node3D = _farmers[id]["node"]
	return node.position


## A brick place on the ground, as a point in the world.
func _at(p: Vector2i) -> Vector3:
	return Vector3((p.x + 0.5) * world.B, (world.GROUND + 1) * world.H, (p.y + 0.5) * world.B)
