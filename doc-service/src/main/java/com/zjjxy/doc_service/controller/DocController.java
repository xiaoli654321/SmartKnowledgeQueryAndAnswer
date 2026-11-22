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
 * 文档接口（方法体留空给你写）。
 * <p>
 * 【注解思路】
 * - @RestController + @RequestMapping("/doc")：路径前缀必须和服务约定一致
 *   （以后网关按 Path=/doc/** 转发，前缀对不上就是 404）；
 * - 上传用 @PostMapping("/upload")，参数 @RequestParam("file") MultipartFile：
 *   表单字段名必须叫 file，前端/curl 的 -F 也要用这个名字；
 * - 每个方法上的 @RequirePermission 由 PermissionAspect 拦截（Step6-1）。
 * <p>
 * 【每个方法就一行事】：把 MultipartFile 拆成 filename + bytes，交给 DocService。
 * 参数校验（空文件、空标题）放这里，业务规则放 Service —— 别在 Controller 里写业务。
 */
@RestController
@RequestMapping("/doc")
public class DocController {

    private final DocService docService;

    public DocController(DocService docService) {
        this.docService = docService;
    }

    /**
     * 【待实现】上传新文档
     * 表单三个字段：file（文件）、title（标题）、tenantId（租户）
     * 要做：
     * 1) 参数校验：file 为 null 或 isEmpty → IllegalArgumentException("上传文件为空")；
     *              title 为 null 或 isBlank → IllegalArgumentException("标题不能为空")；
     * 2) return docService.upload(title.trim(), tenantId, file.getOriginalFilename(), file.getBytes());
     */
    @PostMapping("/upload")
    @RequirePermission("document:write")
    public UploadResult upload(@RequestParam("file") MultipartFile file,
                               @RequestParam("title") String title,
                               @RequestParam("tenantId") Long tenantId) throws IOException {
        if(file == null ||file.isEmpty()){
            throw new IllegalArgumentException("上传文件为空");
        }
        if(title == null || title.isBlank()){
            throw new IllegalArgumentException("标题不能为空");
        }
        return docService.upload(title.trim() , tenantId , file.getOriginalFilename() , file.getBytes());
    }

    /**
     * 【待实现】给已有文档上传新版本
     * 表单：file、tenantId（docId 走路径变量）
     * 要做：校验 file 非空 → docService.addVersion(id, tenantId, file.getOriginalFilename(), file.getBytes())
     */
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

    /**
     * 列出本租户的文档（后台管理用）。
     * 路径是 GET /doc（无路径变量），与 GET /doc/{id} 不冲突。
     */
    @GetMapping
    @RequirePermission("document:read")
    public List<KbDocument> list(@RequestParam("tenantId") Long tenantId,
                                 @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return docService.list(tenantId, limit);
    }

    /**
     * 【待实现】查文档
     * GET /doc/{id}?tenantId=1001 → docService.getDocument(id, tenantId)
     * 读接口用 document:read：guest 也能调。
     */
    @GetMapping("/{id}")
    @RequirePermission("document:read")
    public KbDocument get(@PathVariable("id") Long id,
                          @RequestParam("tenantId") Long tenantId) {
        return  docService.getDocument(id, tenantId);
    }

    /**
     * 【待实现】查版本列表（按 version 升序，最后一个是当前版本）
     */
    @GetMapping("/{id}/versions")
    @RequirePermission("document:read")
    public List<KbDocumentVersion> versions(@PathVariable("id") Long id,
                                            @RequestParam("tenantId") Long tenantId) {
        return docService.getVersion(id, tenantId);
    }

    /**
     * 【待实现】回滚到指定版本（会追加一条新版本，不删历史）
     * POST /doc/{id}/rollback?tenantId=1001&version=1 → docService.rollback(id, tenantId, version)
     */
    @PostMapping("/{id}/rollback")
    @RequirePermission("document:write")
    public KbDocument rollback(@PathVariable("id") Long id,
                               @RequestParam("tenantId") Long tenantId,
                               @RequestParam("version") int version) {
        return docService.rollback(id, tenantId, version);
    }
}
