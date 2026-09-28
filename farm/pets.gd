extends Node3D
## The farm's pets (docs/farm-growth.md), who watch the agents. The dog, from level 2, runs to a
## farmer whose turn failed or is resting off a rate limit and barks at it until the trouble
## passes, then goes back to the farmhouse. The cat, from level 4, naps on the porch while every
## farmer is idle, and sits up the moment one of them has work.

## Where the dog waits when nobody needs barking at, and where the cat keeps to on the porch.
const KENNEL := Vector2i(16, 26)
const PORCH := Vector2i(24, 25)
const DOG_PACE := 2.6
const BROWN := Color(0.55, 0.33, 0.14)
const TAN := Color(0.85, 0.66, 0.4)
const GINGER := Color(0.93, 0.55, 0.18)

var world: Node3D
## The live farm, which knows where each farmer stands.
var live: Node3D

var _dog: Node3D
var _dog_bark: Label3D
var _cat: Node3D
var _cat_body: Node3D
var _cat_nap: Label3D
var _villagers: Array = []
var _clock := 0.0


func _ready() -> void:
	_dog = _pet(_dog_bricks())
	_dog.position = _at(KENNEL)
	_dog_bark = _tag("Woof!")
	_dog_bark.position = Vector3(0, 0.9, 0)
	_dog.add_child(_dog_bark)
	_cat = _pet(_cat_bricks())
	_cat_body = _cat.get_child(0)
	_cat.position = _at(PORCH) + Vector3(0, world.H, 0)
	_cat.rotation.y = PI * 0.5
	_cat_nap = _tag("z z")
	_cat_nap.position = Vector3(0, 0.6, 0)
	_cat.add_child(_cat_nap)
	_dog.visible = false
	_cat.visible = false


## The pets the level has brought, from the snapshot's `pets`, and the farmers they watch.
func apply(pets: Array, villagers: Array) -> void:
	_dog.visible = "dog" in pets
	_cat.visible = "cat" in pets
	_villagers = villagers


func _process(delta: float) -> void:
	_clock += delta
	if _dog.visible:
		_watch(delta)
	if _cat.visible:
		_doze()


## The dog's round: to the first farmer in trouble and barking at it, or home to the kennel.
func _watch(delta: float) -> void:
	var goal := _at(KENNEL)
	var trouble := false
	for v in _villagers:
		if v.get("failed", false) or str(v.get("activity", "")) == "RESTING":
			var at: Variant = live.farmer_at(str(v.get("id", "")))
			if at != null:
				goal = (at as Vector3) + Vector3(0.7, 0, 0.35)
				trouble = true
				break
	var gap := Vector3(goal.x - _dog.position.x, 0, goal.z - _dog.position.z)
	var ground := _at(KENNEL).y
	if gap.length() > 0.08:
		_dog.position += gap.normalized() * minf(gap.length(), DOG_PACE * delta)
		_dog.rotation.y = lerp_angle(_dog.rotation.y, atan2(-gap.z, gap.x), minf(1.0, delta * 8.0))
		_dog.position.y = ground + absf(sin(_clock * 16.0)) * 0.06
		_dog_bark.visible = false
		return
	# there: a bark is a little hop towards the farmer, twice a second
	var bark := trouble and fmod(_clock, 0.5) < 0.2
	_dog.position.y = ground + (0.08 if bark else 0.0)
	_dog_bark.visible = trouble and fmod(_clock, 1.0) < 0.6


## The cat asleep on the porch while every farmer is idle, curled low with its z's rising; awake
## and sitting up, tail swishing, while any farmer has work.
func _doze() -> void:
	var idle := true
	for v in _villagers:
		if str(v.get("activity", "IDLE")) != "IDLE":
			idle = false
			break
	_cat_nap.visible = idle
	_cat_nap.position.y = 0.55 + fmod(_clock * 0.25, 0.3)
	_cat_body.scale.y = 0.6 if idle else 1.0
	_cat_body.rotation.x = 0.0 if idle else sin(_clock * 3.0) * 0.08


func _dog_bricks() -> Array:
	var items := []
	for lx in [0, 3]:
		for lz in [0, 1]:
			items.append([Vector3i(lx - 2, 0, lz), BROWN])
	for x in 4:
		for z in 2:
			items.append([Vector3i(x - 2, 1, z), BROWN if z == 0 or x != 1 else TAN])
	items.append([Vector3i(2, 2, 0), BROWN])
	items.append([Vector3i(2, 2, 1), BROWN])
	items.append([Vector3i(3, 2, 0), TAN])
	items.append([Vector3i(3, 2, 1), TAN])
	items.append([Vector3i(2, 3, 0), world.BLACK])
	items.append([Vector3i(2, 3, 1), world.BLACK])
	items.append([Vector3i(-3, 2, 0), BROWN])
	return items


func _cat_bricks() -> Array:
	var items := []
	for x in 3:
		items.append([Vector3i(x - 1, 0, 0), GINGER])
	items.append([Vector3i(1, 1, 0), GINGER])
	items.append([Vector3i(2, 1, 0), world.WHITE])
	items.append([Vector3i(1, 2, 0), GINGER])
	items.append([Vector3i(-2, 1, 0), GINGER])
	items.append([Vector3i(-2, 2, 0), world.WHITE])
	return items


## A pet of bricks at seven tenths of a brick's size, facing +x, its body a node of its own to
## squash.
func _pet(items: Array) -> Node3D:
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.use_colors = true
	mm.mesh = world.brick_mesh
	mm.instance_count = items.size()
	var size := 0.7
	for i in items.size():
		var p: Vector3i = items[i][0]
		var at := Vector3(p.x * world.B, p.y * world.H, p.z * world.B) * size
		mm.set_instance_transform(i, Transform3D(Basis().scaled(Vector3.ONE * size), at))
		mm.set_instance_color(i, items[i][1])
	var mesh := MultiMeshInstance3D.new()
	mesh.multimesh = mm
	mesh.material_override = world.material
	var node := Node3D.new()
	node.add_child(mesh)
	add_child(node)
	return node


func _tag(text: String) -> Label3D:
	var label := Label3D.new()
	label.text = text
	label.font_size = 22
	label.pixel_size = 0.011
	label.outline_size = 8
	label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	label.no_depth_test = true
	label.modulate = Color(1, 0.97, 0.9)
	return label


## A brick place on the ground, as a point in the world.
func _at(p: Vector2i) -> Vector3:
	return Vector3((p.x + 0.5) * world.B, (world.GROUND + 1) * world.H, (p.y + 0.5) * world.B)
