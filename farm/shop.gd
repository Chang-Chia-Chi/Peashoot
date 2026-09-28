extends Node3D
## The farm's shop (docs/farm-growth.md): a board by the paddock gate listing what the app says the
## farm can buy, and what it has bought that stands: the coop and barn as far as they have been
## upgraded, the farmhouse's wing, and the greenhouse over the back row of beds. Clicking a line of
## the board prints what it sells, as the "For sale" sign does; the app decides whether it is sold.

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
## The farmhouse's wing, east of it: its north-west corner, and its size.
const WING := Vector2i(33, 15)
const WING_SIZE := Vector2i(8, 9)
## The greenhouse over the back row of beds: its north-west corner, its size, and its bays.
const GREENHOUSE := Vector2i(7, 34)
const GREENHOUSE_SIZE := Vector2i(49, 11)
const BAY := 12
const PANE := Color(0.72, 0.9, 1.0, 0.28)
const NAMES := {
	"hen": "Hen",
	"cow": "Cow",
	"sheep": "Sheep",
	"pig": "Pig",
	"coop": "Coop +4",
	"barn": "Barn +4",
	"house": "House wing",
	"greenhouse": "Greenhouse",
}
const GOLD := Color(1, 0.85, 0.3)
const GREY := Color(0.75, 0.75, 0.72)
## How far apart the board's lines stand, in world units.
const LINE := 0.34

var world: Node3D

## Each line of the board: its label and what it sells.
var _lines: Array = []
var _head: Label3D
var _built := []
var _layer: MultiMeshInstance3D
var _glass: MultiMeshInstance3D


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


## The board's lines from the snapshot's `herd`, and the coop, barn, farmhouse wing and greenhouse
## drawn as `herd` and `buildings` say.
func apply(herd: Dictionary, buildings: Dictionary) -> void:
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
			price = "built" if item == "greenhouse" else "full"
		label.text = "%s · %s" % [NAMES.get(item, item), price]
		label.modulate = GOLD if why == null else GREY
		label.position = _at(BOARD) + Vector3(0, 14 * world.H + (shop.size() - i) * LINE, 0)
		_lines[i][1] = item
	_head.position = _at(BOARD) + Vector3(0, 14 * world.H + (shop.size() + 1) * LINE, 0)
	_build(
		[
			mini(int(herd.get("coop", 0)), MOST_PARTS - 1) + 1,
			mini(int(herd.get("barn", 0)), MOST_PARTS),
			int(buildings.get("house", 0)),
			buildings.get("greenhouse", false),
		]
	)


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


## What has been bought and stands, from [param parts]: the henhouse's parts, the barn's lean-to
## parts, the farmhouse's extensions and whether the greenhouse is built. Drawn apart from the
## island, so a purchase rebuilds only these, and the greenhouse's panes in a layer of their own
## that the beds show through.
func _build(parts: Array) -> void:
	if parts == _built:
		return
	_built = parts
	var saved: Dictionary = world.bricks
	world.bricks = {}
	_henhouse(parts[0])
	_lean_to(parts[1])
	_wing(parts[2])
	var panes := {}
	if parts[3]:
		panes = _greenhouse()
	var drawn: Dictionary = world.bricks
	world.bricks = saved
	for layer in [_layer, _glass]:
		if layer:
			layer.queue_free()
	_layer = _instances(drawn, world.material)
	var glass := StandardMaterial3D.new()
	glass.vertex_color_use_as_albedo = true
	glass.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	glass.roughness = 0.08
	_glass = _instances(panes, glass)
	if _glass:
		_glass.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF


## [param bricks] as one layer of the world in [param mat], or null when there are none.
func _instances(bricks: Dictionary, mat: Material) -> MultiMeshInstance3D:
	if bricks.is_empty():
		return null
	var items := []
	for p in bricks:
		items.append(
			[Transform3D(Basis(), Vector3(p.x * world.B, p.y * world.H, p.z * world.B)), bricks[p]]
		)
	return world.instances(world.brick_mesh, items, mat)


## The farmhouse's wing, in the same logs, a storey taller for each extension past the first, with
## a lit window a storey and a roof stepping up to its ridge.
func _wing(extensions: int) -> void:
	if extensions == 0:
		return
	var y: int = world.GROUND + 1
	var high := 5 + 3 * (extensions - 1)
	var x := WING.x
	var z := WING.y
	world.box(x, y, z, WING_SIZE.x, 1, WING_SIZE.y, world.STONE_DARK)
	for k in range(1, high):
		world.box(x, y + k, z, WING_SIZE.x, 1, WING_SIZE.y, world.LOG if k % 2 else world.WOOD)
	for storey in extensions:
		world.box(x + 3, y + 2 + storey * 3, z + WING_SIZE.y, 2, 2, 1, world.GLASS)
	for step in (WING_SIZE.y + 1) / 2:
		world.box(
			x - 1,
			y + high + step,
			z - 1 + step,
			WING_SIZE.x + 2,
			1,
			WING_SIZE.y + 2 - 2 * step,
			world.ROOF
		)


## The greenhouse: a white frame over the back row of beds, bays of posts along each long side and
## a ridge down the middle, and its panes, returned apart from the frame for their own layer.
func _greenhouse() -> Dictionary:
	var y: int = world.GROUND + 1
	var x0 := GREENHOUSE.x
	var z0 := GREENHOUSE.y
	var x1 := x0 + GREENHOUSE_SIZE.x - 1
	var z1 := z0 + GREENHOUSE_SIZE.y - 1
	var mid := z0 + GREENHOUSE_SIZE.y / 2
	for x in range(x0, x1 + 1, BAY):
		for z in [z0, z1]:
			world.box(x, y, z, 1, 6, 1, world.WHITE)
	world.box(x0, y + 6, z0, GREENHOUSE_SIZE.x, 1, 1, world.WHITE)
	world.box(x0, y + 6, z1, GREENHOUSE_SIZE.x, 1, 1, world.WHITE)
	world.box(x0, y + 9, mid, GREENHOUSE_SIZE.x, 1, 1, world.WHITE)
	var frame: Dictionary = world.bricks
	world.bricks = {}
	for x in range(x0, x1 + 1):
		for z in range(z0, z1 + 1):
			var rise := 3 - int(absf(z - mid) * 3.0 / (mid - z0))
			if not frame.has(Vector3i(x, y + 6 + rise, z)):
				world.put(x, y + 6 + rise, z, PANE)
			var side := x in [x0, x1] or z in [z0, z1]
			var tall := 6 if side else 0
			for k in tall:
				if not frame.has(Vector3i(x, y + k, z)):
					world.put(x, y + k, z, PANE)
	var panes: Dictionary = world.bricks
	world.bricks = frame
	return panes


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
