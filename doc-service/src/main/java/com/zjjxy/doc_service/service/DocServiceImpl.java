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

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 文档服务实现。
 * <p>
 * 这里唯一"不显然"的设计是【事件发布时机】：
 * 不能在 @Transactional 方法体里直接发消息 —— 一旦后面抛异常事务回滚，
 * 消息已经出去了，search-service 建好了索引、库里却没有这篇文档，长期不一致。
 * 正确做法是把发布动作注册到事务同步回调，只在 afterCommit 执行。
 * <p>
 * 所有读写都强制带 tenant_id：A 租户不能碰到 B 租户的文档（多租户越权最容易漏的地方）。
 */
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
        docMapper.insert(doc);              // 插入后 MP 会把自增 id 回填到 doc.id

        int version = 1;
        insertVersion(doc.getId(), version, text);

        // 事务提交成功之后才发事件（见类注释）
        DocumentIndexedEvent event = buildEvent(doc, version, chunks);
        afterCommit(() -> publisher.publish(event));

        log.info("文档已保存 docId={} tenantId={} version={} chunks={} chars={}",
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

        log.info("新版本已保存 docId={} version={} chunks={}", docId, version, chunks.size());
        return new UploadResult(docId, version, chunks.size(), text.length());
    }

    // ────────────────────────── 查询 ──────────────────────────
    @Override
    public KbDocument get(Long docId, Long tenantId) {
        return requireDoc(docId, tenantId);
    }

    @Override
    public List<KbDocumentVersion> versions(Long docId, Long tenantId) {
        requireDoc(docId, tenantId);        // 先确认这篇文档属于该租户，防止越权列版本
        return versionMapper.selectList(new QueryWrapper<KbDocumentVersion>()
                .eq("document_id", docId)
                .orderByAsc("version"));
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
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在：" + version);
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

    // ────────────────────────── 内部工具 ──────────────────────────

    /** 解析 + 基础校验：解析空文本通常意味着扫描件 / 空文件，早点失败比建空索引好 */
    private String parseAndValidate(String filename, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        String text = parserFactory.parse(filename, bytes);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("解析结果为空，可能是扫描件（图片型 PDF）或空文档");
        }
        return text;
    }

    /** 切片 + 基础校验 */
    private List<String> chunkAndValidate(String text) {
        List<String> chunks = chunker.split(text);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文本切片结果为空");
        }
        return chunks;
    }

    /** 插一条版本记录 */
    private void insertVersion(Long docId, int version, String content) {
        KbDocumentVersion v = new KbDocumentVersion();
        v.setDocumentId(docId);
        v.setVersion(version);
        v.setContent(content);
        versionMapper.insert(v);
    }

    /** 按 id + tenantId 查文档，查不到统一 404（不区分"不存在"和"不是你的"，避免探测） */
    private KbDocument requireDoc(Long docId, Long tenantId) {
        KbDocument doc = docMapper.selectOne(new QueryWrapper<KbDocument>()
                .eq("id", docId)
                .eq("tenant_id", tenantId));
        if (doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在：" + docId);
        }
        return doc;
    }

    /** 组装事件；chunks 在这里转成事件的 Chunk 结构（index 用于回溯原文位置） */
    private DocumentIndexedEvent buildEvent(KbDocument doc, int version, List<String> chunks) {
        List<DocumentIndexedEvent.Chunk> payload = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String c = chunks.get(i);
            payload.add(new DocumentIndexedEvent.Chunk(i, c, c.length()));
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
     * 把动作注册到"事务提交之后"执行。
     * 没有事务（比如单元测试里直接调用）就地执行，保证行为一致。
     */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}