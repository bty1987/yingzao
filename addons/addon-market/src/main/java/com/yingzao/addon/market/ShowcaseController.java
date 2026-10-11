package com.yingzao.addon.market;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yingzao.core.web.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 案例库端点:城市/人物字典(含使用中删除守卫)/案例 CRUD+上下架(删除仅下架态,级联图片)/
 * 图片增删;报名走 InboundController 的 showcase 入口(复用进线链)。
 */
@RestController
@RequestMapping("/api/market/showcase")
public class ShowcaseController {

    public record CityRequest(String name, String pinyin, Integer status) {
    }

    public record PersonRequest(String name, String pinyin, Integer type, String title, Integer status) {
    }

    public record CaseRequest(Long cityId, Long designerId, Long supervisorId, String name, String village,
            String style, String layout, Long area, Integer cycle, String coverUrl, Integer type,
            List<ShowcaseService.PictureItem> pictures) {
    }

    public record CaseStatusRequest(Integer status) {
    }

    public record PictureRequest(String url, Integer sort) {
    }

    private final ShowcaseService showcase;

    public ShowcaseController(ShowcaseService showcase) {
        this.showcase = showcase;
    }

    // ---- 城市 ----

    @PostMapping("/cities")
    public ApiResponse<Map<String, Object>> createCity(@RequestBody CityRequest body, HttpServletRequest request) {
        long id = showcase.createCity(orgId(request), body.name(), body.pinyin());
        return ApiResponse.ok(Map.of("id", id));
    }

    @PutMapping("/cities/{id}")
    public ApiResponse<Void> updateCity(@PathVariable long id, @RequestBody CityRequest body,
            HttpServletRequest request) {
        showcase.updateCity(orgId(request), id, body.name(), body.pinyin(), body.status());
        return ApiResponse.ok();
    }

    @DeleteMapping("/cities/{id}")
    public ApiResponse<Void> deleteCity(@PathVariable long id, HttpServletRequest request) {
        showcase.deleteCity(orgId(request), id);
        return ApiResponse.ok();
    }

    @GetMapping("/cities")
    public ApiResponse<List<Map<String, Object>>> listCities(
            @RequestParam(required = false) Integer status, HttpServletRequest request) {
        return ApiResponse.ok(showcase.listCities(orgId(request), status));
    }

    // ---- 人物 ----

    @PostMapping("/persons")
    public ApiResponse<Map<String, Object>> createPerson(@RequestBody PersonRequest body,
            HttpServletRequest request) {
        long id = showcase.createPerson(orgId(request), body.name(), body.pinyin(),
                body.type() == null ? 0 : body.type(), body.title());
        return ApiResponse.ok(Map.of("id", id));
    }

    @PutMapping("/persons/{id}")
    public ApiResponse<Void> updatePerson(@PathVariable long id, @RequestBody PersonRequest body,
            HttpServletRequest request) {
        showcase.updatePerson(orgId(request), id, body.name(), body.pinyin(), body.type(),
                body.title(), body.status());
        return ApiResponse.ok();
    }

    @DeleteMapping("/persons/{id}")
    public ApiResponse<Void> deletePerson(@PathVariable long id, HttpServletRequest request) {
        showcase.deletePerson(orgId(request), id);
        return ApiResponse.ok();
    }

    @GetMapping("/persons")
    public ApiResponse<List<Map<String, Object>>> listPersons(
            @RequestParam(required = false) Integer type, @RequestParam(required = false) Integer status,
            HttpServletRequest request) {
        return ApiResponse.ok(showcase.listPersons(orgId(request), type, status));
    }

    // ---- 案例 ----

    @PostMapping("/cases")
    public ApiResponse<Map<String, Object>> createCase(@RequestBody CaseRequest body, HttpServletRequest request) {
        long id = showcase.createCase(orgId(request), toCmd(body));
        return ApiResponse.ok(Map.of("id", id));
    }

    @PutMapping("/cases/{id}")
    public ApiResponse<Void> updateCase(@PathVariable long id, @RequestBody CaseRequest body,
            HttpServletRequest request) {
        showcase.updateCase(orgId(request), id, toCmd(body));
        return ApiResponse.ok();
    }

    @PostMapping("/cases/{id}/status")
    public ApiResponse<Void> changeCaseStatus(@PathVariable long id, @RequestBody CaseStatusRequest body,
            HttpServletRequest request) {
        showcase.changeCaseStatus(orgId(request), id, body.status() == null ? 0 : body.status());
        return ApiResponse.ok();
    }

    @DeleteMapping("/cases/{id}")
    public ApiResponse<Void> deleteCase(@PathVariable long id, HttpServletRequest request) {
        showcase.deleteCase(orgId(request), id);
        return ApiResponse.ok();
    }

    @GetMapping("/cases")
    public ApiResponse<Map<String, Object>> pageCases(@RequestParam(required = false) Long cityId,
            @RequestParam(required = false) Integer type, @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long designerId, @RequestParam(required = false) Long supervisorId,
            @RequestParam(required = false) String style, @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ApiResponse.ok(showcase.pageCases(orgId(request), cityId, type, status, designerId,
                supervisorId, style, name, page, size));
    }

    @GetMapping("/cases/{id}")
    public ApiResponse<Map<String, Object>> caseDetail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(showcase.caseDetail(orgId(request), id));
    }

    // ---- 图片 ----

    @PostMapping("/cases/{id}/pictures")
    public ApiResponse<Void> addPicture(@PathVariable long id, @RequestBody PictureRequest body,
            HttpServletRequest request) {
        showcase.addPicture(orgId(request), id, body.url(), body.sort());
        return ApiResponse.ok();
    }

    @DeleteMapping("/pictures/{id}")
    public ApiResponse<Void> deletePicture(@PathVariable long id, HttpServletRequest request) {
        showcase.deletePicture(orgId(request), id);
        return ApiResponse.ok();
    }

    private ShowcaseService.CaseCmd toCmd(CaseRequest body) {
        return new ShowcaseService.CaseCmd(body.cityId(), body.designerId(), body.supervisorId(),
                body.name(), body.village(), body.style(), body.layout(), body.area(), body.cycle(),
                body.coverUrl(), body.type(), body.pictures());
    }

    private long orgId(HttpServletRequest request) {
        return (Long) request.getAttribute("yz.orgId");
    }
}
