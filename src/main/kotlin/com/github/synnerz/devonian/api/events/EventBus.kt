package com.github.synnerz.devonian.api.events

import com.github.synnerz.devonian.api.Ping
import com.github.synnerz.devonian.api.Scheduler
import com.github.synnerz.devonian.mixin.accessor.LevelRendererAccessor
import com.github.synnerz.devonian.utils.StringUtils.clearCodes
import com.github.synnerz.devonian.utils.render.Render3DImmediate
import com.github.synnerz.devonian.utils.render.impl.Render3DState
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.*
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.jvm.optionals.getOrNull
import kotlin.reflect.KClass

object EventBus {
    var totalTicks = 0
    var clientTicks = 0
    private val teamRegex = "^team_(\\d+)$".toRegex()
    // keyed by the java class rather than the KClass: `event::class` allocates a fresh
    // `ClassReference` on every call, and `post` runs for every packet, tick and frame
    val events = ConcurrentHashMap<Class<*>, MutableList<EventListener<Event>>>()
    private val prioComparator = Comparator.comparingInt<EventListener<Event>> { it.prio }
    // `hasAnnotation` goes through kotlin-reflect, which is slow enough to show up when the
    // ~1.5k listeners are (re)registered on startup and on every area change. The java
    // annotation API answers the same question, and the answer never changes per class.
    // These MUST stay above the `init` block below: it registers listeners while the object is
    // still initialising, and Kotlin runs property initialisers in declaration order.
    private val threadedCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val orderedCache = ConcurrentHashMap<Class<*>, Boolean>()
    // written from the netty thread as add-entity packets land and cleared from the client thread
    // on a world change, so a plain HashMap can corrupt under them. entries also only ever went
    // away on that world change, so a long session on one server held every entity it had seen.
    private val entityTypes = ConcurrentHashMap<Int, EntityType<*>>()
    private val entityPos = ConcurrentHashMap<Int, Vec3>()
    var _internalSkipPing = Collections.newSetFromMap<Int>(ConcurrentHashMap())!!

    init {
        ClientEntityEvents.ENTITY_LOAD.register { entity, _ ->
            post(EntityJoinEvent(entity))
        }
        ClientEntityEvents.ENTITY_UNLOAD.register { entity, _ ->
            post(EntityLeaveEvent(entity))
        }
        ClientTickEvents.START_CLIENT_TICK.register { post(TickEvent(it, clientTicks++)) }
        LevelRenderEvents.START_MAIN.register {
            (it.levelRenderer() as? LevelRendererAccessor)?.let { Render3DState.bufferSource = it.renderBuffers.bufferSource() }
        }
        ClientLifecycleEvents.CLIENT_STARTED.register { post(GameLoadEvent(it)) }
        ClientLifecycleEvents.CLIENT_STOPPING.register { post(GameUnloadEvent(it)) }
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register { mc, world ->
            WorldChangeEvent(mc, world).post()
            totalTicks = 0
            entityTypes.clear()
            entityPos.clear()
            _internalSkipPing.clear()
        }
        ScreenEvents.BEFORE_INIT.register { _, screen, _, _ ->
            ScreenMouseEvents.allowMouseClick(screen).register { _, event ->
                val event = GuiClickEvent(event.x, event.y, event.button(), true, screen, event)
                post(event)
                !event.isCancelled()
            }

            ScreenMouseEvents.allowMouseRelease(screen).register { _, event ->
                val event = GuiClickEvent(event.x, event.y, event.button(), false, screen, event)
                post(event)
                !event.isCancelled()
            }

            ScreenKeyboardEvents.allowKeyPress(screen).register { _, event ->
                val event = GuiKeyDownEvent(
                    GLFW.glfwGetKeyName(event.key, event.scancode),
                    event.key,
                    event.scancode,
                    screen,
                    event,
                )
                post(event)
                !event.isCancelled()
            }

            ScreenKeyboardEvents.allowKeyRelease(screen).register { _, event ->
                val event = GuiKeyUpEvent(
                    GLFW.glfwGetKeyName(event.key, event.scancode),
                    event.key,
                    event.scancode,
                    screen,
                    event,
                )
                post(event)
                !event.isCancelled()
            }
        }
        LevelRenderEvents.AFTER_BLOCK_OUTLINE_EXTRACTION.register { worldContext, hitResult ->
            val cancel = BeforeBlockOutlineEvent(worldContext, hitResult).post()
            if (cancel) worldContext.levelState().blockOutlineRenderState = null
            !cancel
        }
        ClientReceiveMessageEvents.ALLOW_GAME.register { comp, overlay ->
            val str = comp.string.clearCodes()

            if (overlay) return@register !ActionbarEvent(str, comp).post()

            val specialized = ChatChannelEvent.from(str, comp)
            val b1 = ChatEvent(str, comp).post()
            val b2 = specialized?.post() ?: false

            return@register !b1 && !b2
        }
        ClientReceiveMessageEvents.MODIFY_GAME.register { comp, overlay ->
            var str = comp.string.clearCodes()

            val evn = if (overlay) ModifyActionbarEvent(str, comp)
                else ModifyChatEvent(str, comp)

            evn.post()

            return@register evn.overrideValue
        }

        on<PacketReceivedEvent> { event ->
            when (val packet = event.packet) {
                is ClientboundSoundPacket -> {
                    val sound = packet.sound.unwrapKey().getOrNull()?.identifier() ?: return@on
                    if (onSoundPacket(
                            "${sound.namespace}:${sound.path}",
                            packet.pitch,
                            packet.volume,
                            packet.source,
                            packet.x, packet.y, packet.z,
                            packet.seed,
                            packet.sound.value()
                        )
                    ) event.cancel()
                }

                is ClientboundPlayerInfoUpdatePacket -> {
                    val action = packet.actions().firstOrNull() ?: return@on
                    if (action === ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) {
                        packet.entries().forEach {
                            val name = it.displayName ?: return@forEach
                            TabAddEvent(name.string.clearCodes(), name).post()
                        }
                        return@on
                    }
                    if (action !== ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME) return@on

                    packet.entries().forEach {
                        val name = it.displayName ?: return@forEach
                        TabUpdateEvent(name.string.clearCodes(), name).post()
                    }
                    return@on
                }

                is ClientboundTabListPacket -> {
                    packet.footer.string.split("\n").forEach { TabFooterEvent(it, packet.footer).post() }
                    packet.header.string.split("\n").forEach { TabHeaderEvent(it, packet.header).post() }
                }

                is ClientboundPingPacket -> {
                    if (packet.id >= 0) return@on
                    totalTicks++
                    ServerTickEvent(totalTicks).post()
                }

                is ClientboundSetPlayerTeamPacket -> {
                    if (packet.parameters.isEmpty) return@on
                    val team = packet.parameters?.get() ?: return@on
                    val teamPrefix = team.playerPrefix.string
                    val teamSuffix = team.playerSuffix.string
                    if (teamPrefix.isEmpty()) return@on
                    if (!packet.name.matches(teamRegex)) return@on
                    ScoreboardEvent("${teamPrefix}${teamSuffix.trim()}".clearCodes(), packet).post()
                    return@on
                }

                is ClientboundAddEntityPacket -> {
                    val id = packet.id
                    val type = packet.type
                    entityTypes[id] = type
                    entityPos[id] = Vec3(packet.x, packet.y, packet.z)
                }

                is ClientboundRemoveEntitiesPacket -> {
                    // indexed rather than iterated: this is fastutil's IntList, and a for-each
                    // would box every id just to throw it away again
                    val ids = packet.entityIds
                    for (i in 0 until ids.size) {
                        val id = ids.getInt(i)
                        entityTypes.remove(id)
                        entityPos.remove(id)
                    }
                }

                is ClientboundSetEntityDataPacket -> {
                    val id = packet.id
                    val type = entityTypes[id] ?: return@on
                    val data = packet.packedItems
                    EntityDataEvent(id, type, data).post()
                    val text = getNameFromData(data)
                    if (text != null) NameChangeEvent(id, type, text, text.string).post()
                }

                is ClientboundSetActionBarTextPacket -> {
                    val text = packet.text ?: return@on
                    val message = text.string.clearCodes()

                    ActionbarEvent(message, text).post()
                }

                is ClientboundSetEquipmentPacket -> {
                    val id = packet.entity
                    val type = entityTypes[id] ?: return@on
                    val pos = entityPos[id] ?: return@on
                    EntityEquipmentEvent(
                        id,
                        type,
                        pos,
                        packet.slots.map { Pair(it.first, it.second) },
                    ).post()
                }

                is ClientboundSectionBlocksUpdatePacket -> {
                    MultiBlockUpdateEvent(packet).post()
                }

                is ClientboundBlockUpdatePacket -> {
                    BlockUpdateEvent(packet.pos, packet.blockState).post()
                }

                is ClientboundOpenScreenPacket -> {
                    ServerContainerOpenEvent(packet.containerId, packet.title, packet.title.string).post()
                }

                is ClientboundContainerClosePacket -> {
                    ServerContainerCloseEvent(packet.containerId).post()
                }

                is ClientboundContainerSetContentPacket -> {
                    ServerContainerSetContentEvent(packet.containerId, packet.stateId, packet.items, packet.carriedItem).post()
                }

                is ClientboundContainerSetSlotPacket -> {
                    if (ServerContainerSetSlotEvent(packet.containerId, packet.stateId, packet.item, packet.slot).post())
                        event.cancel()
                }
            }
        }

        on<PrePacketSentEvent> { event ->
            when (val packet = event.packet) {
                is ServerboundUseItemOnPacket -> {
                    UseItemOnEvent(packet.hitResult, packet.hand).post()
                }

                is ServerboundUseItemPacket -> {
                    UseItemEvent(packet.hand).post()
                }

                is ServerboundContainerClosePacket -> {
                    if (ClientContainerCloseEvent(packet.containerId).post())
                        event.cancel()
                }
            }
        }

        ClientLifecycleEvents.CLIENT_STARTED.register { client ->
            PostClientInitEvent(client).post()
        }
    }

    fun onSoundPacket(
        soundEvent: String,
        pitch: Float,
        volume: Float,
        category: SoundSource,
        x: Double, y: Double, z: Double,
        seed: Long,
        underlyingEvent: SoundEvent,
    ): Boolean = SoundPlayEvent(soundEvent, pitch, volume, category, x, y, z, seed, underlyingEvent).post()

    @JvmOverloads
    fun serverTicks(usingPing: Boolean = false): Int =
        if (usingPing) totalTicks + (Ping.getMedianPing() / 50.0 + 10.0).toInt()
        else totalTicks

    private fun getNameFromData(list: List<SynchedEntityData.DataValue<*>>): Component? {
        val idx = when (list.size) {
            8 -> 7
            9 -> 8
            16 -> 10
            17 -> 10
            19 -> 11
            22 -> 11
            else -> -1
        }

        val entry: SynchedEntityData.DataValue<*>?
        if (idx >= 0 && list[idx].id == 2) entry = list[idx]
        else entry = list.find { it.id == 2 }

        val v = entry?.value ?: return null
        return (v as? Optional<*>)?.getOrNull() as? Component
    }

    inline fun <reified T : Event> on(noinline cb: (T) -> Unit): EventListener<T> {
        return on<T>(cb, true)
    }

    inline fun <reified T : Event> on(noinline cb: (T) -> Unit, add: Boolean = true): EventListener<T> {
        val obj = EventListener(cb, T::class)
        if (add) obj.register()
        return obj
    }

    inline fun <reified T : Event> once(noinline cb: (T) -> Unit) {
        var evn: EventListener<T>? = null
        evn = on {
            evn!!.unregister()
            cb(it)
        }
    }

    private fun isThreaded(T: Class<*>): Boolean =
        threadedCache.getOrPut(T) { T.isAnnotationPresent(Threaded::class.java) }

    private fun isOrdered(T: Class<*>): Boolean =
        orderedCache.getOrPut(T) { T.isAnnotationPresent(Ordered::class.java) }

    private fun removeImpl(T: Class<*>, listener: EventListener<*>) {
        events[T]?.remove(listener)
    }

    fun remove(T: KClass<*>, listener: EventListener<*>) {
        val j = T.java
        if (isThreaded(j)) removeImpl(j, listener)
        else Scheduler.scheduleTask { removeImpl(j, listener) }
    }

    private fun addImpl(T: Class<*>, listener: EventListener<Event>) {
        val arr = events.getOrPut(T) {
            if (isThreaded(T)) CopyOnWriteArrayList()
            else ArrayList()
        }
        if (isOrdered(T)) {
            var i = arr.binarySearch(listener, prioComparator)
            if (i < 0) i = -(i + 1)
            arr.add(i, listener)
        } else arr.add(listener)
    }

    fun add(T: KClass<*>, listener: EventListener<Event>) {
        val j = T.java
        if (isThreaded(j)) addImpl(j, listener)
        else Scheduler.scheduleTask { addImpl(j, listener) }
    }

    /** whether anything is listening for [T]; lets hot callers skip allocating the event at all */
    fun hasListeners(T: Class<*>): Boolean = events[T]?.isNotEmpty() ?: false

    fun <T : Event> post(event: T) {
        val listeners = events[event.javaClass] ?: return
        if (listeners.isEmpty()) return

        // indexed rather than `forEach` so the common (non-threaded, ArrayList) case does not
        // allocate an iterator per post
        if (listeners is ArrayList) {
            var i = 0
            while (i < listeners.size) {
                listeners[i].trigger(event)
                i++
            }
            return
        }

        listeners.forEach { cb ->
            cb.trigger(event)
        }
    }
}