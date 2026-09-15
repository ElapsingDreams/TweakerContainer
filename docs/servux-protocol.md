# Servux / Tweakeroo 容器数据协议（逆向记录）

来源：`libs/tweakeroo-fabric-1.21.8-0.25.6.jar`、`libs/servux-fabric-1.21.8-0.7.7.jar` 的字节码反编译。
本文件只记录**事实**（通道、类型号、字节布局、门禁条件），实现见 `client/data`。

## 1. 通道与类型号

- 通道：`servux:tweaks`（tweakeroo `ServuxTweaksHandler.CHANNEL_ID` 的静态初始化里 `ldc "servux"` + `ldc "tweaks"`）
- 报文首部是 **VarInt**（`PacketByteBuf.writeVarInt/readVarInt`，已用 `method_10804/method_10816` → `class_8703` 的 varint 辅助类核实）写的类型号；
  类型号**不是** ordinal，枚举构造器 `Type(String name, int ordinal, int type)` 的第三个参数才是：

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
| 1 / 2（metadata 收发） | `VarInt type` + `NBT` |
| 3（方块实体请求） | `VarInt type` + `VarInt transactionId` + `long pos`（`writeBlockPos`，即 `BlockPos.asLong()`） |
| 4（实体请求） | `VarInt type` + `VarInt transactionId` + `VarInt entityId` |
| 5（方块 NBT 响应，常用） | `VarInt type` + `long pos` + `NBT` |
| 6（实体 NBT 响应） | `VarInt type` + `VarInt entityId` + `NBT` |
| 10 / 11 及 12 / 13 | 分片用的 `buffer`（`PacketSplitter`），单个容器 NBT 走不到 |

> 分片阈值（`servux.network.PacketSplitter` 的常量）：单包总量上限 `1048571` 字节、接收上限 `134217728` 字节。
> 也就是说**只有单条载荷超过约 1 MB 才会分片**；一个容器的方块实体 NBT（哪怕是塞满潜影盒的大箱子）远不到这个量级，
> 而嵌套容器（箱子里的潜影盒里再装东西）的内容本来就随该容器的 NBT 一起下发，不需要额外循环请求。所以分片这条我们不需要实现。

> **已做字节级校验**：用反射调 tweakeroo 的 `toPacket`，与 `client/data/ServuxTweaksPacket` 的输出逐字节比对，
> 方块实体请求（`03 FF FF FF FF 0F 00 01 34 BF FF CE B0 38`）与 metadata 请求都完全一致；
> 反向也用我们自己的解码器读通了 tweakeroo 编出的 `SIMPLE` 响应（`type=5`、坐标与 NBT 都对）。

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
- ⚠️ **本项目不用这条路**（2026-09 实测踩过）：litematica 的 `mixin/network/MixinClientPlayNetworkHandler` 里有个
  `litematica_onQueryResponse(NbtQueryResponseS2CPacket)`，会把客户端收到的**每一个**查询回包交给
  `EntitiesDataStorage.handleVanillaQueryNbt(txId, nbt)`；那个方法第一件事是
  「如果 `checkOpStatus` 还立着就把 `hasOpStatus` 记成有权限」——**它分不清这条回包是谁发的**。
  于是别的模组（我们）发的查询一回包，就可能让它误判自己有查询权限，接着它拿非 OP 的身份去发自己的查询，
  那些请求被服务端静默忽略，它自己的 `transactionToBlockPosOrEntityId` / `pendingBlockEntitiesQueue` /
  `blockEntityCache` 就对不上，之后 `tickCache` 里 `blockEntityCache.get(pos).getLeft()` 拿到 null 直接 NPE 崩游戏
  （0.23.4 的 `EntitiesDataStorage.java:359`；**0.23.7 那行仍然没有判空**）。
  ⇒ 只要客户端装了 litematica，就不要再往这条路上发任何查询包；本项目只保留 Servux 通道与开界面抓取

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

## 7. 通道是共享的：和 tweakeroo 同时装了怎么办

Fabric 的载荷注册表（`PayloadTypeRegistry`）按通道 id 只存**一份**编码器，`register` 撞车时抛 `IllegalArgumentException`：

- tweakeroo 走 malilib 的 `IPluginClientPlayHandler.registerPlayPayload`，那个 default 方法把异常接住、
  打一行 error（`registerPlayPayload: channel ID [{}] is is already registered`），**然后照样 `setPlayRegistered` 继续发包**
- 于是"先注册的那个模组的编码器"会去解"另一个模组的载荷类"：发包时 `ClassCastException`，
  在 netty 编码线程上抛出 → `EncoderException: Failed to encode packet 'serverbound/minecraft:custom_payload' (servux:tweaks)`
  → 客户端以 `Internal Exception` 掉线（单机不触发，因为那时两边都不发包）

因此本项目**先看 tweakeroo 在不在**，在就完全不注册，改成借它的载荷类收发：

- 发包：反射它的 `ServuxTweaksPacket.BlockEntityRequest(BlockPos)` / `MetadataRequest(NbtCompound)`
  与 `ServuxTweaksPacket$Payload(ServuxTweaksPacket)`（全是公开成员，字节与我们的实现一致，见第 2 节）
- 收包：Fabric 的 `GlobalReceiverRegistry` 每个通道也是只留**一个** receiver（`putIfAbsent`，抢不到还不报错），
  那个位置必须留给 tweakeroo，否则它收不到自己的回包；所以我们在
  `CustomPayloadS2CPacket` 的构造器上挂钩子读一份（`client/mixins/CustomPayloadS2CPacketMixin`）
- 发包前先确认它的 codec 真的注册上了（Fabric 没有公开查询接口，只能问 `PayloadTypeRegistryImpl#get(Identifier)`）；
  确认不了就整条通道作废，退回开界面抓取——宁可少一个数据源，也不能发一个编码不了的包把连接打断
