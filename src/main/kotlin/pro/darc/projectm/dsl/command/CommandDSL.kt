package pro.darc.projectm.dsl.command

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import pro.darc.projectm.extension.BukkitDispatchers
import pro.darc.projectm.extension.asText
import pro.darc.projectm.extension.register
import pro.darc.projectm.utils.collection.onlinePlayerMapOf
import kotlin.reflect.KClass

typealias ExecutorBlock<T> = suspend Executor<T>.() -> Unit
typealias CommandBuilderBlock = CommandDSL.() -> Unit
typealias TabCompleterBlock = TabCompleter.() -> List<String>

class TabCompleter(
    val sender: CommandSender,
    val alias: String,
    val args: Array<out String>,
)

open class CommandDSL (
    val plugin: Plugin,
    name: String,
    vararg aliases: String = arrayOf(),
    private var executor: ExecutorBlock<CommandSender>? = null,
    var errorHandler: ErrorHandler = defaultErrorHandler,
    var job: Job = SupervisorJob(),
    val coroutineScope: CoroutineScope = CoroutineScope(job + plugin.BukkitDispatchers.SYNC)
): org.bukkit.command.Command(name.trim()) {
    var onlyInGameMessage = ""
    var cancelOnPlayerDisconnect = true

    private val jobsFromPlayers by lazy { plugin.onlinePlayerMapOf<Job>() }

    init {
        this.aliases = aliases.toList()
    }

    fun errorHandler(handler: ErrorHandler) {
        errorHandler = handler
    }

    // start subcommand
    val subCommands: MutableList<CommandDSL> = mutableListOf()

    open fun subCommandBuilder(name: String, vararg aliases: String = arrayOf()): CommandDSL {
        return CommandDSL(
            plugin = plugin,
            name = name,
            aliases = aliases,
            errorHandler = errorHandler,
            job = job,
            coroutineScope = coroutineScope
        ).also {
            it.permission = this.permission
            it.permissionMessage(this.permissionMessage())
            it.onlyInGameMessage = this.onlyInGameMessage
            it.usageMessage = this.usageMessage
        }
    }

    inline fun command(
        name: String,
        vararg aliases: String = arrayOf(),
        block: CommandBuilderBlock,
    ): CommandDSL {
        return subCommandBuilder(name, *aliases).apply(block).also { subCommands.add(it) }
    }
    // end subcommand

    // start dispatch command by executor type
    private val executors: MutableMap<KClass<out CommandSender>, ExecutorBlock<CommandSender>> = mutableMapOf()

    open fun executor(block: ExecutorBlock<CommandSender>) {
        executor = block
    }

    open fun <T : CommandSender> genericExecutor(clazz: KClass<T>, block: ExecutorBlock<T>) {
        executors[clazz] = block as ExecutorBlock<CommandSender>
    }

    inline fun <reified T : CommandSender> genericExecutor(noinline block: ExecutorBlock<T>) {
        genericExecutor(T::class, block)
    }

    open fun executorPlayer(block: ExecutorBlock<Player>) {
        genericExecutor(Player::class, block)
    }
    // end dispatch command by executor type

    // start tab complete
    private var tabCompleter: TabCompleterBlock? = null

    open fun tabComplete(block: TabCompleterBlock) {
        tabCompleter = block
    }

    override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): List<String> {
        return if (tabCompleter != null) {
            tabCompleter!!.invoke(TabCompleter(sender, alias, args))
        } else {
            defaultTabCompletion(sender, alias, args)
        }
    }

    open fun defaultTabCompletion(sender: CommandSender, alias: String, args: Array<out String>): List<String> {
        if (args.size > 1) {
            val subCommand = subCommands.find { it.name.equals(args.getOrNull(0), true) }
            if (subCommand != null) {
                return subCommand.tabComplete(sender, args.get(0), args.sliceArray(1 until args.size))
            } else {
                emptyList<String>()
            }
        } else if (args.isNotEmpty()) {
            return if (subCommands.isNotEmpty()) {
                subCommands
                    .filter { it.name.startsWith(args[0], true) }
                    .map { it.name }
            } else super.tabComplete(sender, alias, args)
        }
        return super.tabComplete(sender, alias, args)
    }

    fun TabCompleter.default() = defaultTabCompletion(sender, alias, args)
    // end tab complete

    private fun <T> MutableMap<KClass<out CommandSender>, T>.getByInstance(clazz: KClass<*>): T? {
        return entries.find { it.key::class.isInstance(clazz) }?.value
    }

    override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
        if (!permission.isNullOrBlank() && !sender.hasPermission(permission!!)) {
            permissionMessage()?.let { sender.sendMessage(it) }
        } else {
            if (subCommands.isNotEmpty()) {
                val subCommand = args.getOrNull(0)?.let { arg ->
                    subCommands.find {
                        it.name.equals(arg, true) ||
                                it.aliases.any { it.equals(arg, true) }
                    }
                }
                if (subCommand != null) {
                    subCommand.execute(sender, "$label ${args[0]}", args.sliceArray(1 until args.size))
                    return true
                }
            }
            val genericExecutor = executors.getByInstance(sender::class)
            if (genericExecutor != null) {
                coroutineScope.launch {
                    val executorModel = Executor(sender, label, args, this@CommandDSL, coroutineScope)
                    treatFail(executorModel) {
                        genericExecutor.invoke(executorModel)
                    }
                }
            } else {
                val playerExecutor = executors.getByInstance(Player::class)
                if (playerExecutor != null) {
                    if (sender is Player) {
                        val playerJob = Job() // store and cancel when player
                        if (cancelOnPlayerDisconnect) jobsFromPlayers.put(sender, playerJob) { if (it.isActive) it.cancel() }
                        coroutineScope.launch(playerJob) {
                            val executorModel = Executor(sender, label, args, this@CommandDSL, coroutineScope)
                            treatFail(executorModel) {
                                playerExecutor.invoke(executorModel as Executor<CommandSender>)
                            }
                        }
                    } else sender.sendMessage(onlyInGameMessage)
                } else {
                    coroutineScope.launch {
                        val executorModel = Executor(sender, label, args, this@CommandDSL, coroutineScope)
                        treatFail(executorModel) {
                            executor?.invoke(executorModel)
                        }
                    }
                }
            }
        }
        return true
    }

    private suspend fun treatFail(executor: Executor<*>, block: suspend () -> Unit) {
        try {
            block()
        } catch (ex: CommandFailException) {
            ex.senderMessage?.also { executor.sender.sendMessage(it) }
            ex.execute()
        } catch (ex: Throwable) {
            executor.errorHandler(ex)
        }
    }
}

inline fun command(
    name: String,
    vararg aliases: String = arrayOf(),
    plugin: Plugin,
    job: Job = SupervisorJob(),
    coroutineScope: CoroutineScope = CoroutineScope(job + plugin.BukkitDispatchers.SYNC),
    block: CommandBuilderBlock,
) = CommandDSL(plugin, name, *aliases, job = job, coroutineScope = coroutineScope).apply(block).apply {
    register(plugin)
}

inline fun Plugin.command(
    name: String,
    vararg aliases: String = arrayOf(),
    job: Job = SupervisorJob(),
    coroutineScope: CoroutineScope = CoroutineScope(job + BukkitDispatchers.SYNC),
    block: CommandBuilderBlock,
) = command(name, *aliases, plugin = this, job = job, coroutineScope = coroutineScope, block = block)

fun simpleCommand(
    name: String,
    vararg aliases: String = arrayOf(),
    plugin: Plugin,
    description: String,
    block: ExecutorBlock<CommandSender>,
) = command(name, *aliases, plugin = plugin) {
    if (description.isNotBlank()) this.description = description

    executor(block)
}
