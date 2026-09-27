extends Node3D

## The farm's animals and small moving things, which carry on whatever the agents are doing: cows
## wander the paddock and graze, hens scurry and peck, gulls circle over the sea, a rowboat rocks
## by the pier, the weathervane on the silo swings, and butterflies are out from spring to autumn.
## Each is its own node of bricks, so moving one moves nothing else of the island.

## The paddock's inside, in bricks, where the animals keep to: clear of the fence and the trough.
const PADDOCK := Rect2i(62, 37, 15, 11)
## A cow's amble and a hen's scurry, in world units a second.
const COW_PACE := 0.35
const HEN_PACE := 1.1
## How far apart the cows keep when they pick where to go next, in world units; hens go anywhere.
const ROOM := 1.8
const GULLS := 3
const BUTTERFLIES := 6
const WINGS := [
	Color(1.0, 0.85, 0.2), Color(1.0, 1.0, 1.0), Color(0.95, 0.5, 0.75), Color(0.6, 0.75, 1.0)
]
const PINK := Color(0.96, 0.68, 0.66)
const BEAK := Color(1, 0.7, 0.1)
const HULL := Color(0.5, 0.3, 0.16)

var world: Node3D
## Where it is winter, and the butterflies are gone.
var winter := false

var _rng := RandomNumberGenerator.new()
var _cows: Array[Dictionary] = []
var _hens: Array[Dictionary] = []
var _gulls: Array[Dictionary] = []
var _butterflies: Array[Dictionary] = []
var _vane: Node3D
var _vane_heading := 0.0
var _boat: Node3D
var _clock := 0.0


func _ready() -> void:
	_rng.seed = 7
	var cow_body := _shape(_cow_body_bricks())
	var cow_head := _shape(_cow_head_bricks())
	for at in [Vector2i(65, 39), Vector2i(71, 44), Vector2i(76, 46)]:
		_cows.append(_cow(cow_body, cow_head, at))
	var hen := _shape(_hen_bricks())
	for at in [Vector2i(66, 47), Vector2i(72, 39), Vector2i(78, 42)]:
		_hens.append(_animal(hen, at, 0.0))
	var gull := _shape([[Vector3i(0, 0, 0), world.WHITE], [Vector3i(1, 0, 0), world.STONE]])
	var wing := _shape([[Vector3i(0, 0, 1), world.WHITE], [Vector3i(0, 0, 2), world.STONE]])
	for i in GULLS:
		_gulls.append(_gull(gull, wing, i))
	var flutter := _shape([[Vector3i(0, 0, 0), Color.WHITE]], 0.35)
	for i in BUTTERFLIES:
		_butterflies.append(_butterfly(flutter, i))
	_vane = _weathervane()
	_boat = _rowboat()


func _process(delta: float) -> void:
	_clock += delta
	for cow in _cows:
		_amble(cow, delta, COW_PACE, _cows)
		var head: Node3D = cow["head"]
		# grazing is the head down in the grass, and up again every so often to chew
		var down := 0.0 if cow["walking"] else 0.55 + 0.12 * sin(_clock * 2.0 + cow["phase"])
		head.rotation.z = lerpf(head.rotation.z, -down, delta * 3.0)
	for hen in _hens:
		_amble(hen, delta, HEN_PACE, [])
		var body: Node3D = hen["body"]
		var peck := 0.0
		if not hen["walking"]:
			peck = maxf(0.0, sin(_clock * 9.0 + hen["phase"])) * 0.7
		body.rotation.z = -peck
	for gull in _gulls:
		_circle(gull)
	for butterfly in _butterflies:
		_flutter(butterfly)
	# the vane swings to a wind that wanders, and never quite settles
	_vane_heading += (sin(_clock * 0.13) * 1.4 - _vane_heading) * delta * 0.5
	_vane.rotation.y = _vane_heading + sin(_clock * 1.7) * 0.08
	_boat.position.y = 1 * world.H + sin(_clock * 1.1) * 0.03
	_boat.rotation.z = sin(_clock * 0.9) * 0.06
	_boat.rotation.x = sin(_clock * 0.7 + 1.0) * 0.04


## Walks [param animal] toward its goal, and when it gets there stands a while before picking the
## next patch of the paddock.
func _amble(animal: Dictionary, delta: float, pace: float, herd: Array[Dictionary]) -> void:
	var node: Node3D = animal["node"]
	if not animal["walking"]:
		animal["rest"] -= delta
		if animal["rest"] <= 0.0:
			animal["goal"] = _somewhere(animal, herd)
			animal["walking"] = true
		node.position.y = world.H * (world.GROUND + 1)
		return
	var goal: Vector3 = animal["goal"]
	var gap := Vector3(goal.x - node.position.x, 0, goal.z - node.position.z)
	if gap.length() < 0.05:
		animal["walking"] = false
		animal["rest"] = (
			_rng.randf_range(2.0, 7.0) if pace < HEN_PACE else _rng.randf_range(0.6, 2.5)
		)
		return
	var step := gap.normalized() * minf(gap.length(), pace * delta)
	node.position += step
	# the head is +x, so a heading is the angle from +x, turned smoothly rather than snapped
	var heading := atan2(-gap.z, gap.x)
	node.rotation.y = lerp_angle(node.rotation.y, heading, minf(1.0, delta * 4.0))
	var bob := absf(sin(_clock * pace * 14.0)) * (0.03 if pace < HEN_PACE else 0.05)
	node.position.y = world.H * (world.GROUND + 1) + bob


## A spot in the paddock to head for, clear of where the rest of [param herd] stand or are
## headed, so two cows do not walk into one another; a few tries, then wherever the last one fell.
func _somewhere(animal: Dictionary, herd: Array[Dictionary]) -> Vector3:
	var spot := Vector3.ZERO
	for attempt in 8:
		var x := _rng.randf_range(PADDOCK.position.x, PADDOCK.end.x)
		var z := _rng.randf_range(PADDOCK.position.y, PADDOCK.end.y)
		spot = Vector3(x * world.B, 0, z * world.B)
		if _clear(spot, animal, herd):
			break
	return spot


func _clear(spot: Vector3, animal: Dictionary, herd: Array[Dictionary]) -> bool:
	for other in herd:
		if other == animal:
			continue
		var node: Node3D = other["node"]
		var goal: Vector3 = other["goal"]
		var at := Vector3(node.position.x, 0, node.position.z)
		if spot.distance_to(at) < ROOM or spot.distance_to(Vector3(goal.x, 0, goal.z)) < ROOM:
			return false
	return true


func _circle(gull: Dictionary) -> void:
	var node: Node3D = gull["node"]
	var t: float = _clock * gull["speed"] + gull["phase"]
	var centre: Vector3 = gull["centre"]
	var r: float = gull["radius"]
	node.position = centre + Vector3(cos(t) * r, sin(t * 0.5) * 0.3, sin(t) * r * 0.7)
	# flying anticlockwise, the gull faces along the tangent
	node.rotation.y = atan2(-cos(t) * 0.7, -sin(t))
	var flap := sin(_clock * 7.0 + gull["phase"]) * 0.6
	var left: Node3D = gull["left"]
	var right: Node3D = gull["right"]
	left.rotation.x = -flap
	right.rotation.x = flap


func _flutter(butterfly: Dictionary) -> void:
	var node: Node3D = butterfly["node"]
	node.visible = not winter
	var t: float = _clock * 0.6 + butterfly["phase"]
	var home: Vector3 = butterfly["home"]
	node.position = (
		home + Vector3(sin(t) * 0.9, 0.25 + absf(sin(t * 5.3)) * 0.25, cos(t * 1.3) * 0.6)
	)
	node.scale.x = 0.4 + absf(sin(_clock * 18.0 + butterfly["phase"]))


func _cow(body: MultiMesh, head: MultiMesh, at: Vector2i) -> Dictionary:
	var cow := _animal(body, at, _rng.randf_range(0.0, TAU))
	var neck := MultiMeshInstance3D.new()
	neck.multimesh = head
	neck.material_override = world.material
	# the neck, at the front of the body and a little up, is where the head turns down to graze
	var pivot := Node3D.new()
	pivot.position = Vector3(1.5 * world.B, 2 * world.H, 0)
	pivot.add_child(neck)
	cow["node"].add_child(pivot)
	cow["head"] = pivot
	return cow


## An animal standing at [param at] facing +x, resting a moment before it first moves.
func _animal(mm: MultiMesh, at: Vector2i, turned: float) -> Dictionary:
	var node := Node3D.new()
	node.position = Vector3(at.x * world.B, world.H * (world.GROUND + 1), at.y * world.B)
	node.rotation.y = turned
	var body := Node3D.new()
	var mesh := MultiMeshInstance3D.new()
	mesh.multimesh = mm
	mesh.material_override = world.material
	body.add_child(mesh)
	node.add_child(body)
	add_child(node)
	return {
		"node": node,
		"body": body,
		"walking": false,
		"rest": _rng.randf_range(0.5, 4.0),
		"goal": node.position,
		"phase": _rng.randf_range(0.0, TAU),
	}


func _gull(body: MultiMesh, wing: MultiMesh, i: int) -> Dictionary:
	var node := Node3D.new()
	node.scale = Vector3.ONE * 0.8
	var mesh := MultiMeshInstance3D.new()
	mesh.multimesh = body
	mesh.material_override = world.material
	node.add_child(mesh)
	var wings: Array[Node3D] = []
	for side in [1.0, -1.0]:
		var hinge := Node3D.new()
		var w := MultiMeshInstance3D.new()
		w.multimesh = wing
		w.material_override = world.material
		w.scale.z = side
		w.position.z = -0.5 * world.B * side
		hinge.add_child(w)
		node.add_child(hinge)
		wings.append(hinge)
	add_child(node)
	# over the sea off the front and the right of the island, high above the barn's roof
	var centres := [Vector3(8, 6.5, 17), Vector3(22, 7.2, 12), Vector3(14, 7.8, 19)]
	return {
		"node": node,
		"left": wings[0],
		"right": wings[1],
		"centre": centres[i % centres.size()],
		"radius": 2.2 + i * 0.6,
		"speed": 0.35 + i * 0.07,
		"phase": i * 2.1,
	}


func _butterfly(mm: MultiMesh, i: int) -> Dictionary:
	var node := MultiMeshInstance3D.new()
	node.multimesh = mm
	var m := StandardMaterial3D.new()
	m.albedo_color = WINGS[i % WINGS.size()]
	m.emission_enabled = true
	m.emission = m.albedo_color
	m.emission_energy_multiplier = 0.4
	node.material_override = m
	add_child(node)
	# over the meadows: by the farmhouse, the well's apple tree and the lane's far end
	var spots := [
		Vector2i(40, 20), Vector2i(56, 26), Vector2i(8, 28), Vector2i(86, 30), Vector2i(30, 57)
	]
	var spot: Vector2i = spots[i % spots.size()]
	var home := Vector3(spot.x * world.B, (world.GROUND + 2) * world.H, spot.y * world.B)
	return {"node": node, "home": home, "phase": i * 1.7}


## The black arrow and cross on top of the silo, which the barn drew as still bricks before.
func _weathervane() -> Node3D:
	var vane := Node3D.new()
	var items := []
	for y in 3:
		items.append([Vector3i(0, y, 0), world.BLACK])
	for x in [-2, -1, 1, 2]:
		items.append([Vector3i(x, 2, 0), world.BLACK])
	items.append([Vector3i(-2, 3, 0), world.BLACK])
	items.append([Vector3i(2, 1, 0), world.BLACK])
	var mesh := MultiMeshInstance3D.new()
	mesh.multimesh = _shape(items)
	mesh.material_override = world.material
	vane.add_child(mesh)
	vane.position = Vector3(86.5 * world.B, (world.GROUND + 24) * world.H, 13.5 * world.B)
	add_child(vane)
	return vane


## A rowboat tied up beside the pier, rocking on the swell.
func _rowboat() -> Node3D:
	var items := []
	for x in range(-2, 3):
		for z in range(-1, 2):
			var rim := absi(x) == 2 or absi(z) == 1
			items.append([Vector3i(x, 0, z), world.WOOD_DARK if rim else HULL])
			if rim and absi(x) < 2:
				items.append([Vector3i(x, 1, z), world.WOOD])
	items.append([Vector3i(0, 1, 0), world.WOOD])
	var boat := Node3D.new()
	var mesh := MultiMeshInstance3D.new()
	mesh.multimesh = _shape(items)
	mesh.material_override = world.material
	boat.add_child(mesh)
	boat.position = Vector3(18.5 * world.B, world.H, 63.5 * world.B)
	boat.rotation.y = PI / 2
	add_child(boat)
	return boat


## The cow as `main.gd` used to draw it into the island, less its head, centred on its middle.
func _cow_body_bricks() -> Array:
	var items := []
	for lx in [0, 4]:
		for lz in [0, 2]:
			items.append([Vector3i(lx - 3, 0, lz - 1), world.WHITE])
	for x in 5:
		for y in [1, 2]:
			for z in 3:
				var patch: bool = (x in [1, 2] and y == 2) or (x == 3 and y == 1 and z == 2)
				items.append([Vector3i(x - 3, y, z - 1), world.BLACK if patch else world.WHITE])
	return items


## The cow's head, from the neck: white, a pink muzzle at the front and two black horns.
func _cow_head_bricks() -> Array:
	var items := []
	for z in 3:
		items.append([Vector3i(0, 0, z - 1), world.WHITE])
		items.append([Vector3i(1, 0, z - 1), PINK])
		items.append([Vector3i(0, 1, z - 1), world.WHITE])
		items.append([Vector3i(1, 1, z - 1), world.WHITE])
	items.append([Vector3i(0, 2, -1), world.BLACK])
	items.append([Vector3i(0, 2, 1), world.BLACK])
	return items


func _hen_bricks() -> Array:
	var items := []
	for x in 2:
		for z in 2:
			items.append([Vector3i(x - 1, 0, z), world.WHITE])
	items.append([Vector3i(0, 1, 0), world.WHITE])
	items.append([Vector3i(0, 2, 0), world.RED])
	items.append([Vector3i(1, 1, 0), BEAK])
	return items


## Bricks at whole-brick offsets, drawn as one multimesh of the island's own brick, [param size]
## times as big.
func _shape(items: Array, size := 1.0) -> MultiMesh:
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.use_colors = true
	mm.mesh = world.brick_mesh
	mm.instance_count = items.size()
	for i in items.size():
		var p: Vector3i = items[i][0]
		var at := Vector3(p.x * world.B, p.y * world.H, p.z * world.B) * size
		mm.set_instance_transform(i, Transform3D(Basis().scaled(Vector3.ONE * size), at))
		mm.set_instance_color(i, items[i][1])
	return mm
