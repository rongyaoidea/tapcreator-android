package com.tapcreator.app.backend.service

import com.tapcreator.app.backend.auth.AuthService
import com.tapcreator.app.backend.media.MediaStore
import com.tapcreator.app.backend.model.ModelRouter
import com.tapcreator.app.backend.providers.ProviderGateway
import com.tapcreator.app.data.db.AgentRunEntity
import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import com.tapcreator.app.data.db.AppDatabase
import com.tapcreator.app.data.db.CardEntity
import com.tapcreator.app.data.db.CardLinkEntity
import com.tapcreator.app.data.db.MessageEntity
import com.tapcreator.app.data.db.TaskEntity
import com.tapcreator.app.data.model.AuthFailedException
import com.tapcreator.app.data.model.ChatMessage
import com.tapcreator.app.data.model.GenerationPreferences
import com.tapcreator.app.data.model.MediaKind
import com.tapcreator.app.data.model.Protocol
import com.tapcreator.app.data.model.RunRequest
import com.tapcreator.app.data.model.RunStatus
import com.tapcreator.app.data.model.ConversationSurface
import com.tapcreator.app.data.model.TaskStatus
import com.tapcreator.app.data.model.UpstreamResult
import com.tapcreator.app.data.model.TapcreatorException
import com.tapcreator.app.backend.video.LastFrameExtractor
import com.tapcreator.app.backend.video.FfmpegConcatenator
import com.tapcreator.app.backend.video.Mp4Concatenator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 创作 Run 执行器（M1 后端核心）：
 *  - 设备内直连：经 ModelRouter 定模型 → ChannelRepository 取密钥 → ProviderGateway 调上游；
 *  - 结果归一：媒体落 MediaStore，逐卡写 Room + 卡片关系边（进入链路）；
 *  - 取消/重试：cancel 置 CANCELLED，retry 起新 Run 复用上轮卡片。
 */
@Singleton
class RunService @Inject constructor(
    private val db: AppDatabase,
    private val auth: AuthService,
    private val router: ModelRouter,
    private val channels: ChannelRepository,
    private val gateway: ProviderGateway,
    private val media: MediaStore,
    private val http: okhttp3.OkHttpClient,
    private val ffmpegConcatenator: FfmpegConcatenator,
    @ApplicationContext private val appContext: Context,
) {

    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    /** 记录每个 run 正在执行的生成协程，供 cancel() 及时中断挂起中的上游轮询 */
    private val runningJobs = ConcurrentHashMap<String, Job>()

    /** 视频单段续生成的上限（秒）——按常见视频模型的单段窗口设定，超长时自动分段 */
    private val SEGMENT_SECONDS = 8

    /**
     * 启动自愈：进程被系统杀掉后，上一进程遗留的 PLANNING/RUNNING/PAUSED in-flight run
     * 永远不会有收尾。有 checkpoint 的视频 run 标 PAUSED（可断点续传），无 checkpoint 的标 FAILED。
     * 仅在同进程启动时调用一次。
     */
    suspend fun reconcileStaleRuns() {
        db.agentRunDao().staleActive().forEach { r ->
            val hasCheckpoint = !r.checkpoint.isNullOrBlank()
            db.agentRunDao().update(
                r.copy(
                    status = if (hasCheckpoint) RunStatus.PAUSED else RunStatus.FAILED,
                    error = if (hasCheckpoint) "生成被中断，已保留进度，可恢复续传" else "生成被中断，请重试",
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** 新建对话：返回会话 */
    suspend fun createConversation(token: String, title: String = ""): com.tapcreator.app.data.db.ConversationEntity {
        val user = auth.currentUser(token) ?: throw AuthFailedException("未登录")
        val now = System.currentTimeMillis()
        val conv = com.tapcreator.app.data.db.ConversationEntity(
            id = UUID.randomUUID().toString(),
            userId = user.id,
            title = title.ifBlank { "项目 ${now % 10000}" },
            surface = ConversationSurface.CHAT,
            createdAt = now,
            updatedAt = now,
        )
        db.conversationDao().insert(conv)
        return conv
    }

    /** 发起一次生成 Run（自动建 user 消息、执行子任务），返回 run */
    suspend fun launch(token: String, request: RunRequest): AgentRunEntity {
        val user = auth.currentUser(token) ?: throw AuthFailedException("未登录")
        // 视频是「单条长视频续生成」管道，一次只产出一条；数量强制为 1，避免一次产出多张卡
        val effBase = if (request.kind == MediaKind.VIDEO) request.copy(count = 1) else request
        val resolved = router.resolve(effBase.modelIds, effBase.kind)
        if (resolved.isEmpty()) throw TapcreatorException("没有可用的${effBase.kind} 模型，请先到设置配置渠道", "NO_MODEL")
        val model = resolved.first()

        val pref = toPref(effBase, model.id)
        val run = createRunRecord(effBase, model.id)
        db.agentRunDao().update(run.copy(status = RunStatus.RUNNING, updatedAt = System.currentTimeMillis()))

        // 把被参考的媒体路径（手动选素材或 agent 的 reference_folder）归一化为工作区卡片，
        // 使图/视频间的参考关系以「卡片→卡片」呈现并写入关系图，与是否 agent 执行无关。
        val mediaRefCardIds = ensureReferenceCards(run, effBase.referencedAssetPaths)
        val effRequest = if (mediaRefCardIds.isEmpty()) {
            effBase
        } else {
            effBase.copy(referencedAssetIds = (effBase.referencedAssetIds + mediaRefCardIds).distinct())
        }

        val channelId = model.channelId
        runningJobs[run.id] = checkNotNull(currentCoroutineContext()[Job])
        try {
            withContext(Dispatchers.IO) {
                if (effRequest.kind == MediaKind.VIDEO) {
                    launchVideo(run, pref, channelId, effRequest.referencedAssetIds, effRequest.referencedAssetPaths)
                } else {
                    executeLoop(run, pref, channelId, effRequest.referencedAssetIds, effRequest.referencedAssetPaths)
                }
            }
        } finally {
            runningJobs.remove(run.id)
        }
        return run
    }

    private suspend fun createRunRecord(request: RunRequest, modelId: String): AgentRunEntity {
        val now = System.currentTimeMillis()
        val userMsg = db.withTransaction {
            // sequence 取号 + 插入放同一事务，避免并发时取到相同序号导致时间线乱序
            val seq = db.messageDao().maxSequence(request.conversationId) + 1
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = request.conversationId,
                sequence = seq,
                role = "user",
                content = request.prompt,
                kind = request.kind,
                referencedAssetIds = request.referencedAssetIds.joinToString(","),
                runId = null,
                createdAt = now,
            ).also { db.messageDao().insert(it) }
        }

        val run = AgentRunEntity(
            id = UUID.randomUUID().toString(),
            conversationId = request.conversationId,
            userMessageId = userMsg.id,
            prompt = request.prompt,
            kind = request.kind,
            status = RunStatus.PLANNING,
            modelIds = modelId,
            requestCount = request.count.coerceAtLeast(1),
            error = null,
            createdAt = now,
            updatedAt = now,
        )
        db.agentRunDao().insert(run)
        // 仅当会话标题仍是默认占位名时才自动以提示词命名，避免每次生成都覆盖用户手动设置的会话名称
        val conv = db.conversationDao().byId(request.conversationId)
        if (conv == null || conv.title.isBlank() || conv.title.startsWith("项目")) {
            db.conversationDao().rename(request.conversationId, summarizeTitle(request.prompt), now)
        }
        return run
    }

    internal fun summarizeTitle(prompt: String): String {
        val clean = prompt.trim().take(18)
        return if (clean.isBlank()) "项目" else clean
    }

    /** 执行主流程：解析渠道与密钥 → 逐卡直连上游 → 落库 */
    private suspend fun executeLoop(
        run: AgentRunEntity,
        pref: GenerationPreferences,
        channelId: String,
        referencedCardIds: List<String>,
        referencedAssetPaths: List<String>,
    ) {
        val channelEntity = channels.byId(channelId)
            ?: return failRun(run.id, "渠道不存在")
        val channel = channels.toDomain(channelEntity)
        val secrets = channels.secrets(channelId)
        channels.assertReady(channel, secrets)

        val modelEntity = resolveEntity(run.modelIds)
            ?: return failRun(run.id, "模型未找到")
        val model = router.toDomain(modelEntity)

        val refs = resolveReferences(referencedCardIds, referencedAssetPaths)
        val enrichedPref = pref.copy(referenceTexts = refs.texts, referenceImages = refs.images)

        var cardSeq = db.cardDao().maxSequence(run.conversationId) + 1
        var completed = 0

        try {
            repeat(run.requestCount) { i ->
                if (cancelFlags[run.id]?.get() == true) {
                    db.agentRunDao().byId(run.id)?.let {
                        db.agentRunDao().update(
                            it.copy(status = RunStatus.CANCELLED, error = "已取消", updatedAt = System.currentTimeMillis())
                        )
                    }
                    return
                }
                db.agentRunDao().byId(run.id)?.let { cur ->
                    if (cur.status != RunStatus.RUNNING) db.agentRunDao().update(cur.copy(status = RunStatus.RUNNING))
                }
                val result = gateway.create(channel, secrets, model, enrichedPref)
                persistOneCard(run, modelEntity, i, result, cardSeq, referencedCardIds, enrichedPref.promptEnhanced)
                cardSeq++
                completed++
            }
            db.agentRunDao().byId(run.id)?.let {
                db.agentRunDao().update(it.copy(status = RunStatus.COMPLETED, updatedAt = System.currentTimeMillis()))
            }
        } catch (e: CancellationException) {
            // 用户取消：协程已取消，须在 NonCancellable 下继续提交状态
            withContext(NonCancellable) {
                db.agentRunDao().byId(run.id)?.let {
                    db.agentRunDao().update(it.copy(status = RunStatus.CANCELLED, error = "已取消", updatedAt = System.currentTimeMillis()))
                }
            }
        } catch (e: TapcreatorException) {
            finishWith(run.id, e.message ?: "生成失败")
            throw e
        } catch (e: Exception) {
            finishWith(run.id, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * 视频续生成：突破单段时长限制。
     * 目标时长(seconds) > 单段上限时，拆分为多段 → 第 1 段文本出片，后续段以"上一段尾帧"续帧 →
     * 逐段落临时文件，最后用 MediaMuxer 设备端拼成一条长视频并落库为一张卡片。
     */
    private suspend fun launchVideo(
        run: AgentRunEntity,
        pref: GenerationPreferences,
        channelId: String,
        referencedCardIds: List<String>,
        referencedAssetPaths: List<String>,
    ) {
        val channelEntity = channels.byId(channelId) ?: return failRun(run.id, "渠道不存在")
        val channel = channels.toDomain(channelEntity)
        val secrets = channels.secrets(channelId)
        channels.assertReady(channel, secrets)
        val modelEntity = resolveEntity(run.modelIds) ?: return failRun(run.id, "模型未找到")
        val model = router.toDomain(modelEntity)

        val refs = resolveReferences(referencedCardIds, referencedAssetPaths)
        val basePref = pref.copy(referenceTexts = refs.texts, referenceImages = refs.images)
        // 音色参考：用户选中的音频（卡/素材）→ data URI，作为 H3 首段的 reference_audio，实现音画都衔接
        val referenceAudio = refs.audioUri
        // 身份参考：角色/产品的多视角图片+视频 → 作为 H3 首段的 video_file/image_file，固定人物/产品身份
        val identityRefs = com.tapcreator.app.backend.providers.IdentityRefs(
            videos = refs.videoFiles.take(3),
            images = refs.imageFiles.take(6),
        )

        val target = (pref.seconds ?: 5).coerceIn(1, 120)
        val isH3 = channel.protocol == Protocol.MINIMAX_H3
        val durations = segmentDurations(target, isH3)
        val tmpParts = mutableListOf<File>()
        // 断点续传：恢复上次中断时已生成的段文件（checkpoint 记录路径列表 JSON）
        val json = Json { ignoreUnknownKeys = true }
        val checkpointParts: MutableList<String> = run.checkpoint?.let { cp ->
            runCatching {
                json.parseToJsonElement(cp).jsonArray.map { it.jsonPrimitive.content }
            }.getOrNull()?.toMutableList()
        } ?: mutableListOf()
        // 复用已持久化的段文件（仍存在于磁盘的）
        checkpointParts.mapNotNull { p -> File(p).takeIf { it.exists() } }.forEach { tmpParts += it }
        val startIdx = tmpParts.size // 已生成段数，从这里续
        // 长视频分段生成期间启动前台服务保活，避免后台被系统回收中断续写
        GenerationForegroundService.start(appContext, "视频生成中（${target}s）")
        try {
            var continueFrame: ByteArray? = null
            var referenceVideoUrl: String? = null
            // 恢复续帧：非 H3 需要上一段尾帧，从已恢复的最后一段提取
            if (tmpParts.isNotEmpty() && !isH3) {
                continueFrame = LastFrameExtractor.from(tmpParts.last().absolutePath)
            }
            // H3 续写需要上一段的 CDN URL，但中断后 CDN URL 未持久化——只能从头重生成（无 checkpoint 时不进此分支）
            for ((idx, dur) in durations.withIndex().drop(startIdx)) {
                if (cancelFlags[run.id]?.get() == true) {
                    db.agentRunDao().byId(run.id)?.let {
                        db.agentRunDao().update(it.copy(status = RunStatus.CANCELLED, error = "已取消", updatedAt = System.currentTimeMillis()))
                    }
                    cleanup(tmpParts)
                    return
                }
                val segPref = basePref.copy(seconds = dur)
                val result = if (isH3) {
                    // H3 续写：以上一段产出的视频 CDN URL 作为参考视频实现画面/音频延续；
                    // 首段额外带用户选定的音色参考（音频）与角色/产品身份参考（多视角图+视频）
                    val audioRef = if (idx == 0) referenceAudio else null
                    val firstIdentity = if (idx == 0) identityRefs else null
                    gateway.minimaxH3Video(channel, secrets, model, segPref, referenceVideoUrl, firstIdentity, audioRef)
                } else {
                    gateway.createVideoSegment(channel, secrets, model, segPref, continueFrame)
                }
                // 落段文件
                val f = newSegmentFile()
                f.writeUntil(result)
                if (isH3) {
                    referenceVideoUrl = result.mediaUrl
                } else {
                    continueFrame = LastFrameExtractor.from(f.absolutePath)
                }
                tmpParts += f
                // 写 checkpoint：记录已生成段文件路径，中断后可从这里续传
                val paths = tmpParts.map { it.absolutePath }
                val checkpointJson = kotlinx.serialization.json.buildJsonArray {
                    paths.forEach { p -> add(kotlinx.serialization.json.JsonPrimitive(p)) }
                }.toString()
                db.agentRunDao().byId(run.id)?.let {
                    db.agentRunDao().update(it.copy(checkpoint = checkpointJson, updatedAt = System.currentTimeMillis()))
                }
            }
            // 拼接成最终文件；优先用 ffmpeg（容忍编码差异），失败回退 MediaMuxer，仍失败降级保留最大段
            val mergedFile = newFinalFile()
            var concatDegraded = false
            if (tmpParts.size > 1) {
                // 策略 1：ffmpeg 拼接（concat demuxer 无损 → re-encode 容忍差异）
                val ffmpegOk = runCatching { ffmpegConcatenator.concat(tmpParts, mergedFile) }.getOrDefault(false)
                if (!ffmpegOk || !mergedFile.exists() || mergedFile.length() == 0L) {
                    // 策略 2：回退 MediaMuxer（纯 Android API，不依赖沙箱）
                    val muxerOk = Mp4Concatenator.concat(tmpParts, mergedFile)
                    if (!muxerOk || !mergedFile.exists()) {
                        // 降级：两种拼接都失败，取文件最大的一段作为结果
                        tmpParts.maxByOrNull { runCatching { it.length() }.getOrDefault(0L) }
                            ?.let { it.copyTo(mergedFile, overwrite = true) }
                        concatDegraded = true
                    }
                }
            } else {
                tmpParts.firstOrNull()?.let { it.copyTo(mergedFile, overwrite = true) }
            }
            if (!mergedFile.exists()) throw TapcreatorException("视频拼接失败", "VIDEO_CONCAT")
            cleanup(tmpParts)

            val asset = media.persistFromLocal(run.conversationId, run.id, mergedFile, titleFor(run.prompt, 0), MediaKind.VIDEO)
            val cardSeq = db.cardDao().maxSequence(run.conversationId) + 1
            // 拼接降级时在卡片内容标注，让用户知情（结果已保留，无需重试整轮）
            val note = if (concatDegraded) "${run.prompt}\n\n[分段拼接失败，仅保留单段]" else run.prompt
            persistVideoCard(run, modelEntity, asset.id, cardSeq, referencedCardIds, mergedFile.absolutePath, note, basePref.promptEnhanced)
            db.agentRunDao().byId(run.id)?.let {
                db.agentRunDao().update(it.copy(status = RunStatus.COMPLETED, checkpoint = null, updatedAt = System.currentTimeMillis()))
            }
        } catch (e: CancellationException) {
            // 用户取消：协程已取消，须在 NonCancellable 下继续提交状态
            withContext(NonCancellable) {
                // 保留段文件与 checkpoint，用户可恢复续传
                db.agentRunDao().byId(run.id)?.let {
                    db.agentRunDao().update(it.copy(status = RunStatus.PAUSED, error = "已取消，可恢复续传", updatedAt = System.currentTimeMillis()))
                }
            }
        } catch (e: TapcreatorException) {
            // 保留段文件与 checkpoint，不 cleanup——中断后可断点续传
            finishVideoWith(run.id, e.message ?: "视频生成失败")
            throw e
        } catch (e: Exception) {
            finishVideoWith(run.id, e.message ?: e.javaClass.simpleName)
            throw e
        } finally {
            GenerationForegroundService.stop(appContext)
        }
    }

    private suspend fun finishVideoWith(runId: String, error: String) {
        db.agentRunDao().byId(runId)?.let {
            db.agentRunDao().update(it.copy(status = RunStatus.FAILED, error = error, updatedAt = System.currentTimeMillis()))
        }
    }

    /** 计算每段时长：OpenAI 兼容固定单段窗口；H3 每段落在 [4,15] 且尽量均分，保证可拼 */
    internal fun segmentDurations(target: Int, isH3: Boolean): List<Int> {
        if (!isH3) {
            val segCount = ((target - 1) / SEGMENT_SECONDS) + 1
            return (0 until segCount).map { minOf(SEGMENT_SECONDS, target - it * SEGMENT_SECONDS).coerceAtLeast(1) }
        }
        val t = target.coerceIn(4, 120)
        if (t <= 15) return listOf(t)
        val n = (t + 14) / 15
        val base = t / n
        val rem = t % n
        return (0 until n).map { base + if (it < rem) 1 else 0 }
    }

    private fun cleanup(files: List<File>) {
        files.forEach { runCatching { it.delete() } }
    }

    private fun newSegmentFile(): File =
        File(media.mediaRoot(), "seg-${System.currentTimeMillis()}-${UUID.randomUUID().toString().substring(0, 6)}.mp4")

    private fun newFinalFile(): File =
        File(media.mediaRoot(), "video-${System.currentTimeMillis()}-${UUID.randomUUID().toString().substring(0, 8)}.mp4")

    private fun File.writeUntil(result: UpstreamResult) {
        if (result.mediaBytes != null) {
            writeBytes(result.mediaBytes)
        } else if (result.mediaUrl != null) {
            // 复用注入的单例 client（连接池/通用配置），仅对长时大体积下载覆盖超时
            val client = http.newBuilder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            // 下载瞬时断连重试 1 次，最终映射为可读提示
            repeat(2) { i ->
                try {
                    client.newCall(okhttp3.Request.Builder().url(result.mediaUrl).build()).execute().use { resp ->
                        if (!resp.isSuccessful) throw TapcreatorException("视频段下载失败 HTTP ${resp.code}", "UPSTREAM_HTTP")
                        resp.body?.byteStream()?.use { input -> outputStream().use { output -> input.copyTo(output) } }
                    }
                    return
                } catch (e: java.io.IOException) {
                    if (i == 0) Thread.sleep(1_000L)
                }
            }
            delete() // 清理可能已部分写入的文件，避免临时文件泄漏
            throw TapcreatorException("网络连接被中断，请检查网络后重试", "NETWORK")
        } else {
            throw TapcreatorException("上游未返回视频内容", "UPSTREAM_EMPTY")
        }
    }

    private suspend fun persistVideoCard(
        run: AgentRunEntity,
        model: com.tapcreator.app.data.db.ModelOptionEntity,
        assetId: String,
        seq: Int,
        referencedCardIds: List<String>,
        mediaPath: String,
        content: String = run.prompt,
        promptEnhanced: Boolean = false,
    ) {
        // 文本模型为产出卡起名：避免视频卡标题退化成模型 id；失败回退模型名
        val videoTitle = suggestTitle(run.prompt, model.name, 0)
        // 落库坐标同样错位铺开，避免视频卡回到 (0,0) 盖住已有卡片
        val (x, y) = nextCardPosition(run.conversationId)
        val card = CardEntity(
            id = UUID.randomUUID().toString(),
            runId = run.id,
            conversationId = run.conversationId,
            sequence = seq,
            kind = MediaKind.VIDEO,
            title = videoTitle,
            content = content,
            previewPath = null,
            mediaPath = mediaPath,
            status = RunStatus.COMPLETED,
            x = x,
            y = y,
            promptEnhanced = promptEnhanced,
        )
        db.cardDao().insert(card)
        referencedCardIds.distinct().forEach { refId ->
            db.cardLinkDao().insert(
                CardLinkEntity(id = UUID.randomUUID().toString(), fromCardId = refId, toCardId = card.id, role = "reference")
            )
        }
        db.taskDao().insert(
            TaskEntity(
                id = UUID.randomUUID().toString(),
                runId = run.id,
                title = videoTitle,
                type = MediaKind.VIDEO,
                modelId = model.id,
                channelId = model.channelId,
                upstreamTaskId = null,
                prompt = run.prompt,
                status = TaskStatus.COMPLETED,
                resultAssetId = assetId,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun finishWith(runId: String, error: String) {
        db.agentRunDao().byId(runId)?.let {
            db.agentRunDao().update(it.copy(status = RunStatus.FAILED, error = error, updatedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun resolveEntity(modelIds: String): com.tapcreator.app.data.db.ModelOptionEntity? {
        val id = modelIds.split(",").firstOrNull { it.isNotBlank() } ?: return null
        return db.modelOptionDao().byId(id)
    }

    /** 统一解析参考源（卡 + 素材路径）为真实可上送负载 */
    private data class ResolvedRefs(
        val texts: List<String>,
        val images: List<String>,
        val audioUri: String?,
        /** 多视角图片（base64）——用于角色/产品身份锚点 */
        val imageFiles: List<com.tapcreator.app.backend.providers.IdentityFile>,
        /** 多视角视频（base64）——用于角色/产品身份锚点 */
        val videoFiles: List<com.tapcreator.app.backend.providers.IdentityFile>,
    )

    /** 单次生成的参考图片数量上限：多张原图 base64 会把请求体撑到数十 MB，触发网关断连 */
    private companion object {
        const val MAX_REF_IMAGES = 8
        const val MAX_REF_VIDEOS = 2
        /** 参考图缩放最长边：足以支撑多模态识图/身份锚点，且把单张体积压到 ~200KB */
        const val REF_IMAGE_MAX_SIDE = 1536
        const val REF_IMAGE_JPEG_QUALITY = 85
    }

    /**
     * 把被参考的媒体路径归一化为本会话的工作区卡片（已存在则复用其 id），
     * 后续 persistOneCard/persistVideoCard 会为这些卡建立到新产出卡的 reference 边，
     * 从而让「媒体参考」也能在关系图上以卡片形式呈现。
     */
    private suspend fun ensureReferenceCards(run: AgentRunEntity, paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        val ids = LinkedHashSet<String>()
        paths.distinct().forEach { p ->
            if (p.isBlank()) return@forEach
            val existing = db.cardDao().byMediaPathInConversation(run.conversationId, p)
            if (existing != null) {
                ids += existing.id
            } else {
                // runId 使用合成「材料 id」而非 run.id：保证 byRun(run.id) 只包含真正的产出卡，
                // 避免参考卡被 AgentBrain 的 run.lastOrNull() 误当成本轮产出。参考卡仍在本会话显示、并参与建 reference 边。
                val card = CardEntity(
                    id = UUID.randomUUID().toString(),
                    runId = run.id + ".material",
                    conversationId = run.conversationId,
                    sequence = db.cardDao().maxSequence(run.conversationId) + 1,
                    kind = kindForPath(p),
                    title = p.substringAfterLast('/').substringBeforeLast('.').ifBlank { "参考素材" },
                    content = "",
                    previewPath = null,
                    mediaPath = p,
                    status = RunStatus.COMPLETED,
                    x = 0f,
                    y = 0f,
                )
                db.cardDao().insert(card)
                ids += card.id
            }
        }
        return ids.toList()
    }

    internal fun kindForPath(path: String): MediaKind = when {
        path.endsWith(".mp4") || path.endsWith(".mov") || path.endsWith(".webm") ||
            path.endsWith(".mkv") -> MediaKind.VIDEO
        path.endsWith(".mp3") || path.endsWith(".wav") || path.endsWith(".m4a") ||
            path.endsWith(".aac") -> MediaKind.AUDIO
        else -> MediaKind.IMAGE
    }

    /**
     * 把用户选中的参考（本会话卡片 id + 跨会话素材路径）统一解析成可上送的负载：
     *  - 文本 → 取 content 拼入提示；
     *  - 图片 → 读本地文件转 data URI（既走 OpenAI 多模态，也作为 H3 身份锚点）；
     *  - 音频 → 转 data URI 作为 H3 的 reference_audio（音色参考）；
     *  - 视频 → 转 base64 作为角色/产品身份参考（H3 首段 video_file）。
     */
    private suspend fun resolveReferences(
        cardIds: List<String>,
        assetPaths: List<String>,
    ): ResolvedRefs {
        val texts = mutableListOf<String>()
        val images = mutableListOf<String>()
        val imageFiles = mutableListOf<com.tapcreator.app.backend.providers.IdentityFile>()
        val videoFiles = mutableListOf<com.tapcreator.app.backend.providers.IdentityFile>()
        var audio: String? = null

        fun addImage(path: String?) {
            val f = path?.let(::File)?.takeIf { it.exists() } ?: return
            // 数量上限：多张原图 base64 内联会把请求体撑到数十 MB，触发网关断连；超限直接截断
            if (images.size >= MAX_REF_IMAGES) return
            runCatching {
                val compressed = compressImageToBase64(f.absolutePath)
                if (compressed != null) {
                    images += "data:image/jpeg;base64,$compressed"
                    imageFiles += com.tapcreator.app.backend.providers.IdentityFile(
                        name = f.name,
                        mime = "image/jpeg",
                        base64 = compressed,
                    )
                } else {
                    // 压缩失败降级：仍送原图，保证参考不被静默丢弃
                    val bytes = f.readBytes()
                    val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
                    images += "data:image/webp;base64,$b64"
                    imageFiles += com.tapcreator.app.backend.providers.IdentityFile(
                        name = f.name,
                        mime = "image/webp",
                        base64 = b64,
                    )
                }
            }
        }
        fun addVideo(path: String?) {
            val f = path?.let(::File)?.takeIf { it.exists() } ?: return
            // 视频 base64 体积巨大，身份参考保留前 2 段即可，避免请求体爆炸
            if (videoFiles.size >= MAX_REF_VIDEOS) return
            runCatching {
                val bytes = f.readBytes()
                videoFiles += com.tapcreator.app.backend.providers.IdentityFile(
                    name = f.name,
                    mime = "video/mp4",
                    base64 = java.util.Base64.getEncoder().encodeToString(bytes),
                )
            }
        }
        fun addAudio(path: String?) {
            if (audio != null) return
            val f = path?.let(::File)?.takeIf { it.exists() } ?: return
            runCatching {
                val b64 = java.util.Base64.getEncoder().encodeToString(f.readBytes())
                audio = "data:audio/mpeg;base64,$b64"
            }
        }

        cardIds.distinct().forEach { id ->
            val card = db.cardDao().byId(id) ?: return@forEach
            when (card.kind) {
                // 文本参考 → 内容文本；图像参考 → 多模态图；音频参考 → 音色 data URI
                MediaKind.TEXT -> if (card.content.isNotBlank()) texts += card.content
                MediaKind.IMAGE -> addImage(card.mediaPath ?: card.previewPath)
                MediaKind.AUDIO -> addAudio(card.mediaPath ?: card.previewPath)
                // 视频参考 → 身份/续帧参考（仅视频生成消费；图像生成不读取 videoFiles，天然隔离）
                MediaKind.VIDEO -> addVideo(card.mediaPath ?: card.previewPath)
            }
        }
        assetPaths.distinct().forEach { path ->
            val asset = db.assetDao().byMediaPath(path).firstOrNull() ?: return@forEach
            when (asset.kind) {
                MediaKind.IMAGE -> addImage(path)
                MediaKind.AUDIO -> addAudio(path)
                MediaKind.VIDEO -> addVideo(path)
                else -> Unit
            }
        }
        return ResolvedRefs(texts, images, audio, imageFiles, videoFiles)
    }

    /**
     * 参考图发送前压缩：按最长边采样缩放 + JPEG 编码，把相册原图（数 MB）压到 ~200KB，
     * 避免多张原图 base64 内联把请求体撑到数十 MB 导致网关断连（"software caused connection abort"）。
     * 返回失败时调用方降级送原图。两次 inSampleSize 解码避免大图一次性解到内存 OOM。
     */
    private fun compressImageToBase64(path: String): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > REF_IMAGE_MAX_SIDE * 2 || bounds.outHeight / sample > REF_IMAGE_MAX_SIDE * 2) {
                sample *= 2
            }
            val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val w = decoded.width
            val h = decoded.height
            val scale = REF_IMAGE_MAX_SIDE.toFloat() / maxOf(w, h).toFloat()
            val finalBmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (w * scale).toInt().coerceAtLeast(1),
                    (h * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                decoded
            }
            if (finalBmp !== decoded) decoded.recycle()
            val bos = ByteArrayOutputStream()
            finalBmp.compress(Bitmap.CompressFormat.JPEG, REF_IMAGE_JPEG_QUALITY, bos)
            finalBmp.recycle()
            java.util.Base64.getEncoder().encodeToString(bos.toByteArray())
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 为落库的成品卡计算画布坐标：沿用 startDraft 的错位铺开规则（24 + 序号*170，每行 6 列）。
     * 修复「多张成品卡全部堆叠在 (0,0)，后生成的卡把先前的卡完全盖住」的问题。
     * 每落一张卡前实时查询当前会话卡片数，保证新卡坐标与已有卡不重叠。
     */
    private suspend fun nextCardPosition(conversationId: String): Pair<Float, Float> {
        val count = runCatching { db.cardDao().listByConversation(conversationId) }
            .getOrNull().orEmpty().size
        val col = 6
        return (24f + (count % col) * 170f) to (24f + (count / col) * 170f)
    }

    private suspend fun persistOneCard(
        run: AgentRunEntity,
        model: com.tapcreator.app.data.db.ModelOptionEntity,
        index: Int,
        result: UpstreamResult,
        seq: Int,
        referencedCardIds: List<String>,
        promptEnhanced: Boolean,
    ) {
        // 文本模型为产出卡起名：避免卡片标题退化成模型 id；失败回退模型名
        val cardTitle = suggestTitle(run.prompt, model.name, index)
        val asset = if (result.kind != MediaKind.TEXT) {
            media.persistFrom(run.conversationId, run.id, result, cardTitle, result.kind)
        } else null

        // 成品卡落库时按已有卡片数错位铺开，避免所有卡堆叠在 (0,0) 相互遮挡
        val (x, y) = nextCardPosition(run.conversationId)
        val card = CardEntity(
            id = UUID.randomUUID().toString(),
            runId = run.id,
            conversationId = run.conversationId,
            sequence = seq,
            kind = run.kind,
            title = cardTitle,
            // 成品卡 content 存提交提示词（供预览展示）；文本生成才存结果文本
            content = if (run.kind == MediaKind.TEXT) result.text.orEmpty() else run.prompt,
            previewPath = asset?.previewPath,
            mediaPath = asset?.mediaPath,
            status = RunStatus.COMPLETED,
            x = x,
            y = y,
            promptEnhanced = promptEnhanced,
        )
        db.cardDao().insert(card)

        // 跨轮引用：新卡与所引用的既有卡建立 reference 边
        referencedCardIds.distinct().forEach { refId ->
            db.cardLinkDao().insert(
                CardLinkEntity(
                    id = UUID.randomUUID().toString(),
                    fromCardId = refId,
                    toCardId = card.id,
                    role = "reference",
                )
            )
        }

        db.taskDao().insert(
            TaskEntity(
                id = UUID.randomUUID().toString(),
                runId = run.id,
                title = cardTitle,
                type = run.kind,
                modelId = model.id,
                channelId = model.channelId,
                upstreamTaskId = null,
                prompt = run.prompt,
                status = TaskStatus.COMPLETED,
                resultAssetId = asset?.id,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** 失败（未开始任何产出）即置 FAILED */
    private suspend fun failRun(runId: String, error: String?) {
        db.agentRunDao().byId(runId)?.let {
            db.agentRunDao().update(
                it.copy(status = RunStatus.FAILED, error = error ?: "生成失败", updatedAt = System.currentTimeMillis())
            )
        }
    }

    suspend fun cancel(runId: String) {
        cancelFlags.getOrPut(runId) { AtomicBoolean(false) }.set(true)
        // 中断正在挂起中的协程（如 H3 轮询/分段等待），使生成及时停止
        runningJobs.remove(runId)?.cancel(CancellationException("用户取消 #$runId"))
        db.agentRunDao().byId(runId)?.let {
            db.agentRunDao().update(it.copy(status = RunStatus.CANCELLED, error = "正在取消…", updatedAt = System.currentTimeMillis()))
        }
    }

    /** retry = 新一轮生成（新 run），引用上轮已生成卡片 */
    suspend fun retry(
        token: String,
        conversationId: String,
        prompt: String,
        kind: MediaKind,
        modelIds: List<String>,
        count: Int,
        ratio: String?,
        quality: String?,
        resolution: String? = null,
        seconds: Int?,
        referencedCardIds: List<String>,
        referencedAssetPaths: List<String>,
        promptEnhanced: Boolean = false,
    ): AgentRunEntity = launch(
        token,
        RunRequest(
            conversationId = conversationId,
            prompt = prompt,
            kind = kind,
            modelIds = modelIds,
            count = count,
            ratio = ratio,
            quality = quality,
            resolution = resolution,
            seconds = seconds,
            referencedAssetIds = referencedCardIds,
            referencedAssetPaths = referencedAssetPaths,
            promptEnhanced = promptEnhanced,
        )
    )

    private fun toPref(request: RunRequest, modelId: String): GenerationPreferences = GenerationPreferences(
        kind = request.kind,
        modelIds = if (request.modelIds.isEmpty()) listOf(modelId) else request.modelIds,
        ratio = request.ratio,
        quality = request.quality,
        resolution = request.resolution,
        seconds = request.seconds,
        count = request.count.coerceAtLeast(1),
        referencedAssetIds = request.referencedAssetIds,
        motion = request.motion,
        cfgScale = request.cfgScale,
        prompt = request.prompt,
        promptEnhanced = request.promptEnhanced,
    )

    internal fun titleFor(prompt: String, index: Int): String {
        // 取提示词前 12 个字作为标题，避免两张卡都显示模型名
        val fromPrompt = prompt.trim().take(12).let { t ->
            if (t.length >= 3) t else null
        }
        return if (fromPrompt != null) {
            if (index == 0) fromPrompt else "$fromPrompt ${index + 1}"
        } else {
            "卡片 ${index + 1}"
        }
    }

    /**
     * 用默认文本模型优化用户的创作提示词：结构化扩写模式。
     * 市场主流做法（行业工具/行业工具/行业工具）：保留用户原文作为核心主体，
     * 在原文基础上补充质量修饰词、环境光影、风格细节，不替换不删改原文。
     * 优化后校验原文关键内容是否完整保留，若脱离原意则回退原文。
     */
    suspend fun optimizePrompt(prompt: String): String {
        val modelEntity = router.models(MediaKind.TEXT).firstOrNull { it.enabled }
            ?: router.defaultModel(MediaKind.TEXT)
            ?: return prompt.trim()
        val channelEntity = channels.byId(modelEntity.channelId) ?: return prompt.trim()
        val channel = channels.toDomain(channelEntity)
        val secrets = channels.secrets(channelEntity.id)
        if (secrets.apiKey.isBlank()) return prompt.trim()
        val result = runCatching {
            gateway.agentChat(
                channel, secrets, router.toDomain(modelEntity),
                listOf(
                    ChatMessage(
                        role = "system",
                        
                        content = """你是 AIGC 提示词扩写助手。用户会给一句创作诉求，你的任务是把它扩写为结构清晰、可直接用于图片/视频生成的提示词。

扩写规则：
1. 【必须完整保留】用户原文中的所有内容——主体、动作、场景、色调、风格等，原文每一个关键短语都必须逐字或近乎逐字出现在结果中。你只是在原文基础上补充，不是替换或改写。
2. 【只补充不删改】你可以在用户原文后面补充：质量修饰词（如 high quality, detailed, professional）、环境细节（如 soft lighting, natural light）、风格标签（如 cinematic, photorealistic）、构图说明（如 close-up, wide angle）。
3. 【禁止替换】不得把用户的主体替换为其他主体，不得改变用户指定的色调和氛围。如果用户说"白猫"你不能改成"黑猫"或"机器猫"。
4. 【禁止加对立元素】不得添加与用户原文矛盾的内容（如用户要明亮你不得加 dark/gloomy）。
5. 输出格式：用户原文 + 补充的修饰词，用逗号分隔，英文补充词为主（适配生图模型），中文原文保留。
6. 只输出扩写后的提示词本身，不要任何解释、编号或多余文字。

示例：
用户：一只白猫坐在窗台上晒太阳
输出：一只白猫坐在窗台上晒太阳, soft warm sunlight, cozy home interior, detailed fur, photorealistic, cinematic lighting, high quality, 8k""",
                    ),
                    ChatMessage(role = "user", content = prompt),
                ),
                null,
            )
        }.getOrNull() ?: return prompt.trim()
        val optimized = result.trim().trim('“', '”', '"', '。')
        // 防脱离原意兜底：只保留与原文重叠度足够高的优化结果
        return if (optimized.isBlank() || !coversOriginal(optimized, prompt)) prompt.trim() else optimized
    }

    /**
     * 用默认文本模型优化视频创作提示词：分镜优化 电影级分镜扩写模式。
     * 调研来源：AgentBrain 的 分镜优化 分镜原则 + MiniMax/Hailuo/Kling 视频模型官方提示词指南。
     * 视频提示词需要时序结构（按秒分段）、运镜描述、光影与声音，与图片提示词结构不同。
     * 保留用户原文核心创意，按电影分镜结构扩写。
     */
    suspend fun optimizeVideoPrompt(prompt: String): String {
        val modelEntity = router.models(MediaKind.TEXT).firstOrNull { it.enabled }
            ?: router.defaultModel(MediaKind.TEXT)
            ?: return prompt.trim()
        val channelEntity = channels.byId(modelEntity.channelId) ?: return prompt.trim()
        val channel = channels.toDomain(channelEntity)
        val secrets = channels.secrets(channelEntity.id)
        if (secrets.apiKey.isBlank()) return prompt.trim()
        val result = runCatching {
            gateway.agentChat(
                channel, secrets, router.toDomain(modelEntity),
                listOf(
                    ChatMessage(
                        role = "system",
                        content = """你是视频提示词分镜扩写助手。用户会给一句创作诉求，你的任务是把它扩写为结构清晰、可直接用于视频生成的分镜提示词。

分镜扩写规则：
1. 【必须完整保留】用户原文中的所有内容——主体、动作、场景、色调、风格等，原文每一个关键短语都必须出现在结果中。
2. 【分镜结构】按以下结构组织：核心创意 → 时间轴节奏（按秒分段，如 0-2s/2-5s/5-10s）→ 视觉构图（景别/机位）→ 动态运镜（推/拉/摇/移/跟，只选一种）→ 光影细节 → 声音与合成。
3. 【禁止替换】不得把用户的主体替换为其他主体，不得改变用户指定的色调和氛围。
4. 【禁止加对立元素】不得添加与用户原文矛盾的内容。
5. 【避免】文本字幕、水印、变脸、过度抖动、物理穿帮。
6. 输出格式：保留中文原文核心，分镜描述用中文+英文关键词混合，逗号分隔。
7. 只输出扩写后的提示词本身，不要任何解释、编号或多余文字。

示例：
用户：一只白猫坐在窗台上晒太阳
输出：一只白猫坐在窗台上晒太阳, 0-3s: 中近景固定机位, 猫眯眼享受阳光, warm sunlight through window, 3-6s: 缓慢推进, 阳光角度变化光影流动, 6-10s: 特写猫爪, soft focus background, natural lighting, ambient sound: purring cat, gentle breeze, cinematic, high quality, 1080p""",
                    ),
                    ChatMessage(role = "user", content = prompt),
                ),
                null,
            )
        }.getOrNull() ?: return prompt.trim()
        val optimized = result.trim().trim('“', '”', '"', '。')
        return if (optimized.isBlank() || !coversOriginal(optimized, prompt)) prompt.trim() else optimized
    }

    /** 判断优化结果是否仍保留用户原意。
     *  中英文统一容错：英文按词元、中文按相邻两字 bigram 匹配，重点容忍「中间插入英文/空格修饰」；
     *  对语序重排仍较敏感（相邻 bigram 被拆散会掉命中）——这是有意为之：语序/内容大改即视为脱离原意回退原文。
     *  只要优化结果保留 ≥60% 的原文关键词元即达标，避免把中英文优化结果误判为「脱离原意」回退原文。 */
    private fun coversOriginal(optimized: String, original: String): Boolean {
        if (original.isBlank()) return true
        val normOpt = optimized.lowercase()
        val normOrig = original.lowercase()
        val grams = mutableListOf<String>()
        // 英文/数字词元（长度>=2，忽略 a/of 等单字虚词）
        Regex("[a-z0-9]{2,}").findAll(normOrig).forEach { grams += it.value }
        // 中文连续串按相邻两字 bigram 拆（中间插入英文/空格修饰也不至于整体失配）
        Regex("[\\u4e00-\\u9fff]+").findAll(normOrig).forEach { run ->
            val s = run.value
            if (s.length == 1) grams += s
            else for (i in 0 until s.length - 1) grams += s.substring(i, i + 2)
        }
        if (grams.isEmpty()) return true
        val hit = grams.count { g -> normOpt.contains(g) }
        return hit.toFloat() / grams.size >= 0.6f
    }

    /**
     * 为产出卡起一个贴合内容的简短标题：用文本模型根据提示词生成，
     * 避免手动创作时卡片名就是模型 id。无可用文本渠道/超时/调用失败时回退为模型名。
     */
    private suspend fun suggestTitle(prompt: String, modelName: String, index: Int): String {
        val title = withTimeoutOrNull(3000) {
            runCatching {
                val modelEntity = router.models(MediaKind.TEXT).firstOrNull { it.enabled }
                    ?: router.defaultModel(MediaKind.TEXT)
                    ?: return@runCatching null
                val channelEntity = channels.byId(modelEntity.channelId) ?: return@runCatching null
                val channel = channels.toDomain(channelEntity)
                val secrets = channels.secrets(channelEntity.id)
                channels.assertReady(channel, secrets)
                gateway.agentChat(
                    channel, secrets, router.toDomain(modelEntity),
                    listOf(
                        ChatMessage(
                            role = "system",
                            content = "你是创作作品命名助手。请用简洁中文给下面这条创作诉求起一个贴切名称，不超过 12 个字。只输出名称本身，不要引号、句号、编号或任何其它文字。",
                        ),
                        ChatMessage(role = "user", content = prompt.ifBlank { modelName }),
                    ),
                    null,
                ).trim().trim('“', '”', '"', '。', '.', ' ', '\n', '\t')
            }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= 24 }
        }
        return title ?: titleFor(prompt, index)
    }
}