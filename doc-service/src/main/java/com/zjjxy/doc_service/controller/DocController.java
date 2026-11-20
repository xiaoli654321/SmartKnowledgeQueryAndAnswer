package com.zjjxy.doc_service.controller;

import com.zjjxy.doc_service.dto.UploadResult;
import com.zjjxy.doc_service.entity.KbDocument;
import com.zjjxy.doc_service.entity.KbDocumentVersion;
import com.zjjxy.doc_service.rbac.RequirePermission;
import com.zjjxy.doc_service.service.DocService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 文档接口。
 * <p>
 * 路径前缀 /doc 必须和网关的路由断言 Path=/doc/** 对上，否则走网关就是 404。
 * 参数校验放在这里（空文件、空标题），业务规则放 Service。
 */
@RestController
@RequestMapping("/doc")
public class DocController {

    private final DocService docService;

    public DocController(DocService docService) {
        this.docService = docService;
    }

    /** 上传新文档（multipart 表单：file / title / tenantId） */
    @PostMapping("/upload")
    @RequirePermission("document:write")
    public UploadResult upload(@RequestParam("file") MultipartFile file,
                               @RequestParam("title") String title,
                               @RequestParam("tenantId") Long tenantId) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        return docService.upload(title.trim(), tenantId, file.getOriginalFilename(), file.getBytes());
    }

    /** 给已有文档上传新版本 */
    @PostMapping("/{id}/version")
    @RequirePermission("document:write")
    public UploadResult addVersion(@PathVariable("id") Long id,
                                   @RequestParam("tenantId") Long tenantId,
                                   @RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        return docService.addVersion(id, tenantId, file.getOriginalFilename(), file.getBytes());
    }

    /** 查文档 */
    @GetMapping("/{id}")
    @RequirePermission("document:read")
    public KbDocument get(@PathVariable("id") Long id,
                          @RequestParam("tenantId") Long tenantId) {
        return docService.get(id, tenantId);
    }

    /** 查版本列表（按版本号升序） */
    @GetMapping("/{id}/versions")
    @RequirePermission("document:read")
    public List<KbDocumentVersion> versions(@PathVariable("id") Long id,
                                            @RequestParam("tenantId") Long tenantId) {
        return docService.versions(id, tenantId);
    }

    /** 回滚到指定版本（会追加一条新版本，不删历史） */
    @PostMapping("/{id}/rollback")
    @RequirePermission("document:write")
    public KbDocument rollback(@PathVariable("id") Long id,
                               @RequestParam("tenantId") Long tenantId,
                               @RequestParam("version") int version) {
        return docService.rollback(id, tenantId, version);
    }
}