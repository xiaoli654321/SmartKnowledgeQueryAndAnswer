package com.zjjxy.doc_service.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zjjxy.doc_service.chunk.TextChunker;
import com.zjjxy.doc_service.dto.UploadResult;
import com.zjjxy.doc_service.entity.KbDocument;
import com.zjjxy.doc_service.entity.KbDocumentVersion;
import com.zjjxy.doc_service.event.DocumentIndexedEvent;
import com.zjjxy.doc_service.kafka.DocumentEventPublisher;
import com.zjjxy.doc_service.mapper.KbDocumentMapper;
import com.zjjxy.doc_service.mapper.KbDocumentVersionMapper;
import com.zjjxy.doc_service.parser.ParserFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class DocServiceImpl implements DocService {

    private static final Logger log = LoggerFactory.getLogger(DocServiceImpl.class);

    private final KbDocumentMapper docMapper;
    private final KbDocumentVersionMapper versionMapper;
    private final ParserFactory parserFactory;
    private final TextChunker chunker;
    private final DocumentEventPublisher publisher;

    public DocServiceImpl(KbDocumentMapper docMapper,
                          KbDocumentVersionMapper versionMapper,
                          ParserFactory parserFactory,
                          TextChunker chunker,
                          DocumentEventPublisher publisher) {
        this.docMapper = docMapper;
        this.versionMapper = versionMapper;
        this.parserFactory = parserFactory;
        this.chunker = chunker;
        this.publisher = publisher;
    }

    // ────────────────────────── 上传新文档 ──────────────────────────
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UploadResult upload(String title, Long tenantId, String filename, byte[] bytes) throws IOException {
        String text = parseAndValidate(filename, bytes);
        List<String> chunks = chunkAndValidate(text);

        KbDocument doc = new KbDocument();
        doc.setTenantId(tenantId);
        doc.setTitle(title);
        doc.setContent(text);
        docMapper.insert(doc);                 // 插入后 MP 会把自增 id 回填到 doc.id

        int version = 1;
        insertVersion(doc.getId(), version, text);

        // 事务提交后才发事件（一起成功 or 一起失败）
        DocumentIndexedEvent event = buildEvent(doc, version, chunks);
        afterCommit(() -> publisher.publish(event));

        log.info("文档保存 docId={} tenantId={} version={} chunks={} chars={}",
                doc.getId(), tenantId, version, chunks.size(), text.length());
        return new UploadResult(doc.getId(), version, chunks.size(), text.length());
    }

    // ────────────────────────── 追加新版本 ──────────────────────────
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UploadResult addVersion(Long docId, Long tenantId, String filename, byte[] bytes) throws IOException {
        KbDocument doc = requireDoc(docId, tenantId);
        String text = parseAndValidate(filename, bytes);
        List<String> chunks = chunkAndValidate(text);

        int version = versionMapper.selectMaxVersion(docId) + 1;
        insertVersion(docId, version, text);

        // 主表只更新正文：版本号在版本表里，主表没有 version 列
        KbDocument patch = new KbDocument();
        patch.setId(docId);
        patch.setContent(text);
        docMapper.updateById(patch);

        DocumentIndexedEvent event = buildEvent(doc, version, chunks);
        afterCommit(() -> publisher.publish(event));

        log.info("新版本已经保存 docId={} version={} chunks={}", docId, version, chunks.size());
        return new UploadResult(docId, version, chunks.size(), text.length());
    }

    // ────────────────────────── 查询 ──────────────────────────
    @Override
    public KbDocument getDocument(Long docId, Long tenantId) {
        return requireDoc(docId, tenantId);
    }

    // ────────────────────────── 版本列表 ──────────────────────────
    @Override
    public List<KbDocumentVersion> getVersion(Long docId, Long tenantId) {
        requireDoc(docId, tenantId);          // 先确认文档属于该租户，防止越权列别人的版本
        // 升序排列：第 1 个是 v1，最后一个才是最新版本（要"最新在前"就改 orderByDesc）
        return versionMapper.selectList(new QueryWrapper<KbDocumentVersion>()
                .eq("document_id", docId)
                .orderByAsc("version"));
    }

    // ────────────────────────── 文档列表 ──────────────────────────
    @Override
    public List<KbDocument> list(Long tenantId, int limit) {
        // 强制带 tenant_id；按 id 倒序即"最新在前"
        return docMapper.selectList(new QueryWrapper<KbDocument>()
                .eq("tenant_id", tenantId)
                .orderByDesc("id")
                .last("limit " + Math.max(1, Math.min(limit, 100))));
    }

    // ────────────────────────── 回滚 ──────────────────────────
    @Override
    @Transactional(rollbackFor = Exception.class)
    public KbDocument rollback(Long docId, Long tenantId, int version) {
        KbDocument doc = requireDoc(docId, tenantId);
        KbDocumentVersion target = versionMapper.selectOne(new QueryWrapper<KbDocumentVersion>()
                .eq("document_id", docId)
                .eq("version", version));
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在: " + version);
        }
        String text = target.getContent();

        // 把老内容写回主表
        KbDocument patch = new KbDocument();
        patch.setId(docId);
        patch.setContent(text);
        docMapper.updateById(patch);

        // ★ 追加一条新版本，而不是删掉中间版本 —— 历史必须可追溯
        int newVersion = versionMapper.selectMaxVersion(docId) + 1;
        insertVersion(docId, newVersion, text);

        List<String> chunks = chunker.split(text);
        DocumentIndexedEvent event = buildEvent(doc, newVersion, chunks);
        afterCommit(() -> publisher.publish(event));

        log.info("已回滚 docId={} 到版本{}，并追加为版本{}", docId, version, newVersion);
        doc.setContent(text);
        return doc;
    }

    // ══════════════════════════════════════════════════════════════
    //  下面这些是我给你准备好的"下一步"：方法签名 + 注释，方法体你来实现
    //  建议实现顺序：parseAndValidate → chunkAndValidate → insertVersion
    //                → requireDoc → buildEvent → afterCommit
    // ══════════════════════════════════════════════════════════════

    /**
     * 【待实现】解析 + 基础校验：byte[] → 纯文本
     * <p>
     * 三步：
     * 1) 空文件校验：bytes 为 null 或 length==0 → IllegalArgumentException("文件内容为空")；
     * 2) 调 parserFactory.parse(filename, bytes) 拿到文本（它会按扩展名挑 Txt/Pdf/Docx 解析器）；
     * 3) 文本空校验：text 为 null 或 isBlank → IllegalArgumentException("解析结果为空，可能是扫描件或空文档")。
     * <p>
     * 为什么第 3 步不能省：图片型 PDF 抽出来是空串，不拦的话会建一篇"没有内容"的文档，
     * 检索时永远命中不了，问题要到很久以后才被发现。
     * <p>
     * ❗注意：这个方法声明了 throws IOException，parserFactory.parse 也会抛，直接往外抛即可。
     */
    private String parseAndValidate(String filename, byte[] bytes) throws IOException {
        if(bytes == null || bytes.length == 0) {
            throw new  IllegalArgumentException("内容文件是空的");
        }
        String text = parserFactory.parse(filename, bytes);
        if(text == null || text.isEmpty()) {
            throw new IllegalArgumentException("解析文档为空，可能是扫描文件(图片类型PDF)或者空文档");
        }
        return text;
    }

    /**
     * 【待实现】切片 + 基础校验
     * <p>
     * 1) chunker.split(text) 得到 List<String>；
     * 2) 如果返回空列表 → 抛 IllegalArgumentException("文本切片结果为空")。
     * <p>
     * 提示：TextChunker 遇到空白文本本来就会返回空列表，这里只是把它翻译成"用户能看懂的 400"。
     */
    private List<String> chunkAndValidate(String text) {
        List<String> chunks = chunker.split(text);
        if(chunks.isEmpty()) {
            throw new IllegalArgumentException("文本切片为空");
        }
        return chunks;
    }

    /**
     * 【待实现】插一条版本记录
     * <p>
     * 1) new KbDocumentVersion()；
     * 2) setDocumentId / setVersion / setContent（注意字段叫 documentId，对应库里的 document_id）；
     * 3) versionMapper.insert(v)。
     * <p>
     * createTime 不用管：实体上有 @TableField(fill = FieldFill.INSERT)，
     * MyMetaObjectHandler 会自动填。
     */
    private void insertVersion(Long docId, int version, String content) {
        KbDocumentVersion v = new KbDocumentVersion();
        // 这里是 document_id，不是主键 id：写成 setId(docId) 会插出 id=文档ID、document_id=NULL，
        // 而 document_id 是 NOT NULL，直接报 Column 'document_id' cannot be null
        v.setDocumentId(docId);
        v.setVersion(version);
        v.setContent(content);
        versionMapper.insert(v);
    }

    /**
     * 【待实现】按 id + tenantId 查文档，查不到抛 404
     * <p>
     * 1) docMapper.selectOne(new QueryWrapper<KbDocument>().eq("id", docId).eq("tenant_id", tenantId))；
     * 2) 为 null → throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在：" + docId)。
     * <p>
     * 为什么必须带 tenant_id：只按 id 查，A 租户能读到 B 租户的文档，这是多租户系统最常见的越权漏洞。
     * 为什么统一 404 而不是"无权限"：不区分"不存在"和"不是你的"，避免被人枚举文档 ID。
     */
    private KbDocument requireDoc(Long docId, Long tenantId) {
        // 主表 kb_document 的主键列是 id（没有 document_id 这一列，那是版本表才有的）
        KbDocument doc = docMapper.selectOne(new QueryWrapper<KbDocument>()
                .eq("id", docId).eq("tenant_id", tenantId)
        );
        if(doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND , "文档不存在" + docId);
        }
        return doc;
    }

    /**
     * 【待实现】把文档 + 切片组装成 Kafka 事件
     * <p>
     * DocumentIndexedEvent 有 7 个字段，逐个填：
     * - eventId   ：UUID.randomUUID().toString()
     * - docId     ：doc.getId()
     * - tenantId  ：doc.getTenantId()
     * - title     ：doc.getTitle()
     * - version   ：传入的 version
     * - chunks    ：把 List<String> 转成 List<DocumentIndexedEvent.Chunk>，
     *               每项 new Chunk(i, content, content.length())，i 从 0 开始
     * - occurredAt：Instant.now().toString()
     * <p>
     * 为什么要 index：检索命中后要告诉用户"出自原文第几段"，靠它回溯。
     */
    private DocumentIndexedEvent buildEvent(KbDocument doc, int version, List<String> chunks) {
            List<DocumentIndexedEvent.Chunk> payload = new ArrayList<>(chunks.size());
            for(int i = 0; i < chunks.size(); i++) {
                String c = chunks.get(i);
                payload.add(new DocumentIndexedEvent.Chunk(i,c,c.length()));
            }
            return new DocumentIndexedEvent(
                    UUID.randomUUID().toString(),
                    doc.getId(),
                    doc.getTenantId(),
                    doc.getTitle(),
                    version,
                    payload,
                    Instant.now().toString());
    }

    /**
     * 【待实现】事务提交之后再执行某个动作（本项目用来发 Kafka 事件）
     * <p>
     * 1) 先判断 TransactionSynchronizationManager.isSynchronizationActive()；
     * 2) 为 true：registerSynchronization(new TransactionSynchronization() {
     *        @Override public void afterCommit() { action.run(); }
     *    });
     * 3) 为 false（没有事务，比如单元测试直接调）：就地 action.run()。
     * <p>
     * ★ 为什么不能直接 publish：
     *   先发消息、后提交事务，一旦事务回滚，检索侧已经建好索引、库里却没有这篇文档，
     *   数据和索引长期不一致，而且没有任何报错提示你。
     */
    private void afterCommit(Runnable action) {
            if(TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
            }else {
                action.run();
            }

    }
}
