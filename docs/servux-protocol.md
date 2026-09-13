# Servux / Tweakeroo 容器数据协议（逆向记录）

来源：`libs/tweakeroo-fabric-1.21.8-0.25.6.jar`、`libs/servux-fabric-1.21.8-0.7.7.jar` 的字节码反编译。
本文件只记录**事实**（通道、类型号、字节布局、门禁条件），实现见 `client/data`。

## 1. 通道与类型号

- 通道：`servux:tweaks`（tweakeroo `ServuxTweaksHandler.CHANNEL_ID` 的静态初始化里 `ldc "servux"` + `ldc "tweaks"`）
- 报文首部是 1 个 int = 类型号；类型号**不是** ordinal，枚举构造器 `Type(String name, int ordinal, int type)` 的第三个参数才是：

| 枚举 | ordinal | 线上 type |
|---|---|---|
| `PACKET_S2C_METADATA` | 0 | **1** |
| `PACKET_C2S_METADATA_REQUEST` | 1 | **2** |
| `PACKET_C2S_BLOCK_ENTITY_REQUEST` | 2 | **3** |
| `PACKET_C2S_ENTITY_REQUEST` | 3 | **4** |
| `PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE` | 4 | **5** |
| `PACKET_S2C_ENTITY_NBT_RESPONSE_SIMPLE` | 5 | **6** |
| `PACKET_S2C_NBT_RESPONSE_START` | 6 | **10** |
| `PACKET_S2C_NBT_RESPONSE_DATA` | 7 | **11** |
| `PACKET_C2S_NBT_RESPONSE_START` | 8 | 12（推断） |
| `PACKET_C2S_NBT_RESPONSE_DATA` | 9 | 13（推断） |

## 2. 字节布局（`ServuxTweaksPacket.toPacket` / `fromPacket`）

| 类型 | 载荷 |
|---|---|
| 1 / 2（metadata 收发） | `int type` + `NBT` |
| 3（方块实体请求） | `int type` + `int transactionId` + `long pos`（`writeBlockPos`，即 `BlockPos.asLong()`） |
| 4（实体请求） | `int type` + `int transactionId` + `int entityId` |
| 5（方块 NBT 响应，常用） | `int type` + `long pos` + `NBT` |
| 6（实体 NBT 响应） | `int type` + `int entityId` + `NBT` |
| 10 / 11 及 12 / 13 | 分片用的 `buffer`（`PacketSplitter`），单个容器 NBT 走不到 |

## 3. 握手与门禁（服务端侧，Servux 0.7.7）

- 客户端发 `PACKET_C2S_METADATA_REQUEST`（type 2），NBT 内容 = `{"version": "<客户端 mod 版本字符串>"}`
  （tweakeroo 用 `Reference.MOD_STRING`；请求里是**字符串**）
- 服务端 `TweaksDataProvider.register(player)` 回 `PACKET_S2C_METADATA`（type 1），并把该玩家标记为 registered
- `TweaksDataProvider.onBlockEntityRequest(player, pos)` 的门禁，缺一不可：
  1. `hasPermission(player)` —— `Permissions.check(player, permNode, permission_level)`，权限等级来自服务端设置 `permission_level`
  2. `isPlayerRegistered(player)` —— **必须先握手**，否则请求被丢弃
  3. `isEnabled()` —— `tweaks_data` 这个 provider 开着
  满足后：`ServerWorld.getBlockEntity(pos)` → `createNbtWithId(registryManager)` → 回 `PACKET_S2C_BLOCK_NBT_RESPONSE_SIMPLE`（取不到方块实体时回空 NBT）
- 元数据响应里 `version` 是**协议版本 int**（tweakeroo 会比对，不匹配就判定 Servux 无效），另有 `servux`（版本字符串）、`stackingShulkers` 等 tweak 开关

## 4. 原版查询通道（Servux 的另一条路）

- 客户端：`ClientPlayNetworkHandler.getDataQueryHandler().queryBlockNbt(pos, callback)`，底层是 `QueryBlockNbtC2SPacket`
- 服务端：`ServerPlayNetworkHandler.onQueryBlockNbt` 要求 `player.hasPermissionLevel(2)`，**不满足时静默 return，不会踢人**
- Servux 的 `MixinServerPlayNetworkHandler_QueryNbt.servux_onQueryBlockNbt(int level)`：当 `EntitiesDataProvider.hasNbtQueryOverride()` 且 `hasNbtQueryPermission(player)` 时把等级改成 0
- ⚠️ **`EntitiesDataProvider` 构造里 `nbt_query_override` 的默认值是 false**（`ServuxBoolSetting("nbt_query_override", …, iconst_0, …)`），
  所以"原版查询"默认在 Servux 服上**用不了**，非 OP 玩家要靠上面的自定义通道

## 5. 复用到的 malilib 设施

- `fi.dy.masa.malilib.network.IClientPayloadData`：`getVersion/getPacketType/getTotalSize/isEmpty/toPacket/clear`
- `fi.dy.masa.malilib.network.IPluginClientPlayHandler<T extends CustomPayload>`：
  `getPayloadChannel`、`registerPlayPayload(PayloadType, PacketCodec, side)`、`encodeClientData(P)`、`decodeClientData(id, P)`、
  `encodeWithSplitter`、`sendPlayPayload`，以及 `ClientPlayNetworking.PlayPayloadHandler<T>` 的 `receivePlayPayload`
- `fi.dy.masa.malilib.network.ClientPlayHandler.getInstance().registerClientPlayHandler(handler)`
- `fi.dy.masa.malilib.network.PacketSplitter`：大 NBT 分片
- tweakeroo 的 `ServuxTweaksHandler` 就是这套接口的实现范例，`EntityDataManager` 是使用范例

## 6. 客户端缓存与复用（tweakeroo 侧，仅作参考）

`EntityDataManager`：`blockEntityCache`（pos → `Pair<时间戳, Pair<BlockEntity, NBT>>`）、`pendingBlockEntitiesQueue`、
`tickCache`/`getCacheTimeout`、`shouldUseQuery()`（有 op 状态或 15 分钟窗口内才查询）、
`handleVanillaQueryNbt(int txId, NBT)`、`handleBlockEntityData(pos, NBT, channel)`、`getBlockInventory(World, BlockPos, boolean)`。
