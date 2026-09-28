extends Node3D
## The animal shop (docs/farm-growth.md): a board by the paddock gate listing what the app says the
## farm can buy, and the coop and barn as far as they have been upgraded. Clicking a line of the
## board prints what it sells, as the "For sale" sign does; the app decides whether it is sold.

## Where the board stands, in bricks: south of the lane, by the paddock's west fence.
const BOARD := Vector2i(57, 34)
## The henhouse's north-west corner, east of the barn's path, and how wide each part of it is.
const COOP := Vector2i(77, 22)
const COOP_PART := 3
## The barn's lean-to, along its west wall, and how long each part of it is.
const LEAN_TO := Vector2i(60, 9)
const LEAN_TO_PART := 4
## The most parts either building shows: past this an upgrade adds room but nothing to see.
const MOST_PARTS := 3
const NAMES := {
	"hen": "Hen", "cow": "Cow", "sheep": "Sheep", "pig": "Pig", "coop": "Coop +4", "barn": "Barn +4"
}
const GOLD := Color(1, 0.85, 0.3)
const GREY := Color(0.75, 0.75, 0.72)
## How far apart the board's lines stand, in world units.
const LINE := 0.34

var world: Node3D

## Each line of the board: its label and what it sells.
var _lines: Array = []
var _head: Label3D
var _built := Vector2i(-1, -1)
var _layer: MultiMeshInstance3D


func _ready() -> void:
	var post := []
	for y in range(1, 12):
		post.append([Vector3i(0, y, 0), world.WOOD_DARK])
	for x in range(-3, 4):
		for y in range(8, 12):
			var edge := x in [-3, 3] or y in [8, 11]
			post.append([Vector3i(x, y, 1), world.WOOD_DARK if edge else world.BARN_RED])
	var body := MultiMeshInstance3D.new()
	body.multimesh = _shape(post)
	body.material_override = world.material
	body.position = _at(BOARD)
	add_child(body)
	_head = _tag("SHOP", GOLD)
	_head.font_size = 24
	add_child(_head)


## The board's lines from the snapshot's `herd`, and the coop and barn drawn to its upgrades.
func apply(herd: Dictionary) -> void:
	var shop: Array = herd.get("shop", [])
	while _lines.size() < shop.size():
		var label := _tag("", GREY)
		add_child(label)
		_lines.append([label, ""])
	for i in _lines.size():
		var label: Label3D = _lines[i][0]
		label.visible = i < shop.size()
		if i >= shop.size():
			continue
		var offer: Dictionary = shop[i]
		var item := str(offer.get("item", ""))
		var why: Variant = offer.get("why")
		var price := "%d coins" % int(offer.get("price", 0))
		if why == "level":
			price = "level %d" % int(offer.get("level", 1))
		elif why == "full":
			price = "full"
		label.text = "%s · %s" % [NAMES.get(item, item), price]
		label.modulate = GOLD if why == null else GREY
		label.position = _at(BOARD) + Vector3(0, 14 * world.H + (shop.size() - i) * LINE, 0)
		_lines[i][1] = item
	_head.position = _at(BOARD) + Vector3(0, 14 * world.H + (shop.size() + 1) * LINE, 0)
	_build(int(herd.get("coop", 0)), int(herd.get("barn", 0)))


## What a click at [param at] buys, when it lands on a line of the board nearer than
## [param reach] pixels, or an empty string.
func hit(camera: Camera3D, at: Vector2, reach: float) -> String:
	var best := reach
	var said := ""
	for line in _lines:
		var label: Label3D = line[0]
		if not label.visible:
			continue
		var d := camera.unproject_position(label.position).distance_to(at)
		if d < best:
			best = d
			said = line[1]
	return said


## The henhouse, a part wider for each coop upgrade, and the barn's lean-to, a part longer for
## each barn upgrade; drawn apart from the island, so an upgrade rebuilds only these.
func _build(coop: int, barn: int) -> void:
	var parts := Vector2i(mini(coop, MOST_PARTS - 1) + 1, mini(barn, MOST_PARTS))
	if parts == _built:
		return
	_built = parts
	var saved: Dictionary = world.bricks
	world.bricks = {}
	_henhouse(parts.x)
	_lean_to(parts.y)
	var drawn: Dictionary = world.bricks
	world.bricks = saved
	if _layer:
		_layer.queue_free()
		_layer = null
	var items := []
	for p in drawn:
		items.append(
			[Transform3D(Basis(), Vector3(p.x * world.B, p.y * world.H, p.z * world.B)), drawn[p]]
		)
	if not items.is_empty():
		_layer = world.instances(world.brick_mesh, items, world.material)


## A little white henhouse on stilts with a red roof and a ramp, a nest box for each part.
func _henhouse(parts: int) -> void:
	var y: int = world.GROUND + 1
	var x: int = COOP.x
	var z: int = COOP.y
	var w := parts * COOP_PART + 1
	for dx in [0, w - 1]:
		for dz in [0, 3]:
			world.box(x + dx, y, z + dz, 1, 2, 1, world.WOOD_DARK)
	world.box(x, y + 2, z, w, 3, 4, world.WHITE)
	for k in parts:
		world.box(x + 1 + k * COOP_PART, y + 3, z + 4, 2, 1, 1, world.HAY)
	world.box(x - 1, y + 5, z - 1, w + 2, 1, 6, world.RED)
	world.box(x, y + 6, z, w, 1, 4, world.RED)
	for k in 3:
		world.put(x + 1, y + k, z + 6 - k, world.WOOD)


## A red lean-to shed along the barn's west wall, with a white-trimmed door on each part.
func _lean_to(parts: int) -> void:
	if parts == 0:
		return
	var y: int = world.GROUND + 1
	var length := parts * LEAN_TO_PART
	var z: int = LEAN_TO.y + 3 * LEAN_TO_PART - length
	world.box(LEAN_TO.x, y, z, 6, 6, length, world.BARN_RED)
	world.box(LEAN_TO.x - 1, y + 6, z - 1, 7, 1, length + 2, world.WHITE)
	world.box(LEAN_TO.x + 1, y + 7, z, 5, 1, length, world.WHITE)
	for k in parts:
		var dz := z + k * LEAN_TO_PART + 1
		world.box(LEAN_TO.x - 1, y, dz, 1, 4, 2, world.BARN_RED.darkened(0.25))
		world.box(LEAN_TO.x - 1, y + 4, dz, 1, 1, 2, world.WHITE)


func _tag(text: String, colour: Color) -> Label3D:
	var label := Label3D.new()
	label.text = text
	label.font_size = 20
	label.pixel_size = 0.011
	label.outline_size = 8
	label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	label.no_depth_test = true
	label.modulate = colour
	return label


func _shape(items: Array) -> MultiMesh:
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
	return mm


## A brick place on the ground, as a point in the world, at the ground's own height.
func _at(p: Vector2i) -> Vector3:
	return Vector3((p.x + 0.5) * world.B, world.GROUND * world.H, (p.y + 0.5) * world.B)
