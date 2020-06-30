package com.github.alenfive.dataway2.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.alenfive.dataway2.datasource.DataSourceManager;
import com.github.alenfive.dataway2.entity.*;
import com.github.alenfive.dataway2.entity.vo.RenameGroupReq;
import com.github.alenfive.dataway2.extend.ApiInfoInterceptor;
import com.github.alenfive.dataway2.extend.IApiPager;
import com.github.alenfive.dataway2.script.IScriptParse;
import com.github.alenfive.dataway2.service.ScriptParseService;
import com.github.alenfive.dataway2.utils.RequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.RequestMethodsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @Description: 将存储的API注册为request mapping,并且提供对入参及存储的执行脚本进行解析。
 * 输出解析后的最终脚本提供给脚本执行器`@Link DataSourceDialect`。然后对结果进行封装返回
 * @Copyright: Copyright (c) 2019  ALL RIGHTS RESERVED.
 * @Author: 米华军
 * @CreateDate: 2020/5/27 16:30
 * @UpdateDate: 2020/5/27 16:30
 * @UpdateRemark: init
 * @Version: 1.0
 * @menu mapping 注册
 */
@SuppressWarnings("DuplicatedCode")
@Slf4j
@Component
public class QLRequestMappingFactory {

    @Autowired
    private HttpServletRequest request;

    @Autowired
    private ScriptParseService parseService;

    private final List<String> blankList = Arrays.asList(
            "/dataway2",
            "/error",
            "/api-ui"
    );

    @Value("${spring.application.name}")
    private String service;

    @Autowired
    private IApiPager apiPager;

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Autowired
    private IScriptParse scriptParse;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSourceManager dataSourceManager;

    public List<ApiInfoInterceptor> interceptors = null;

    private Map<String, ApiInfo> cacheApiInfo = new ConcurrentHashMap<>();

    /**
     * 初始化db mapping
     */
    @PostConstruct
    public void init() throws IOException {

        //加载数据库API
        ApiParams apiParams = new ApiParams().putParam("service",service);
        StringBuilder script = new StringBuilder(dataSourceManager.listApiInfoScript());
        parseService.buildParams(script,apiParams);
        List<Map<String,Object>> apiInfos = dataSourceManager.find(script, ApiInfo.builder().datasource(dataSourceManager.getStoreApiKey()).build(),null);
        for (Map<String,Object> map : apiInfos){
            ApiInfo apiInfo = objectMapper.readValue(objectMapper.writeValueAsBytes(map),ApiInfo.class);
            this.cacheApiInfo.put(buildApiInfoKey(apiInfo),apiInfo);
        }

        //加载代码方式的API
        List<ApiInfo> codeApiList = getPathListForCode();
        for (ApiInfo codeInfo : codeApiList){
            ApiInfo dbInfo = this.cacheApiInfo.get(buildApiInfoKey(codeInfo));
            if (dbInfo != null){
                continue;
            }

            codeInfo.setCreateTime(new Date());
            codeInfo.setUpdateTime(new Date());
            apiParams = ApiParams.builder().param(codeInfo.toMap()).build();
            script = new StringBuilder(dataSourceManager.saveApiInfoScript());
            parseService.buildParams(script,apiParams);
            dataSourceManager.insert(script,ApiInfo.builder().datasource(dataSourceManager.getStoreApiKey()).build(),null);
            this.cacheApiInfo.put(buildApiInfoKey(codeInfo),getDbInfo(codeInfo));
        }

        //注册mapping
        for (ApiInfo apiInfo : this.cacheApiInfo.values()){
            this.registerMappingForApiInfo(apiInfo);
        }
    }

    private String buildApiInfoKey(ApiInfo apiInfo) {
        return apiInfo.getMethod() +" "+ apiInfo.getPath();
    }



    /**
     * 执行脚本逻辑
     * @return
     */
    @ResponseBody
    public Object execute(@PathVariable(required = false) Map<String,String> pathVar,
                          @RequestParam(required = false) Map<String,Object> param,
                          @RequestBody(required = false) Map<String,Object> body) throws Throwable {

        String path = buildPattern(request);
        String method = request.getMethod();
        ApiParams apiParams = ApiParams.builder()
                .pathVar(pathVar)
                .header(RequestUtils.buildHeaderParams(request))
                .param(param)
                .body(body)
                .session(RequestUtils.buildSessionParams(request))
                .request(request)
                .build();


        ApiInfo apiInfo = cacheApiInfo.get(buildApiInfoKey(ApiInfo.builder().method(method).path(path).build()));

        String reaultType = apiInfo.getPath().substring(apiInfo.getPath().lastIndexOf("/")+1);
        if (ApiResultType.page.name().equals(reaultType)){
            Integer pageNo = buildPagerNo(apiParams);
            Integer pageSize = buildPagerSize(apiParams);
            apiParams.putParam(apiPager.getPageNoVarName(),pageNo);
            apiParams.putParam(apiPager.getPageSizeVarName(),pageSize);
            apiParams.putParam(apiPager.getIndexVarName(),apiPager.getIndexVarValue(pageSize,pageNo));
        }

        StringBuilder script = new StringBuilder(URLDecoder.decode(apiInfo.getScript(),"utf-8"));

        return scriptParse.runScript(script.toString(),apiInfo,apiParams);
    }

    private Integer buildPagerNo(ApiParams apiParams) {
        Object value = parseService.buildParamItem(apiParams,apiPager.getPageNoVarName());
        if (StringUtils.isEmpty(value)){
            apiParams.putParam(apiPager.getPageNoVarName(),apiPager.getPageNoDefaultValue());
            return apiPager.getPageNoDefaultValue();
        }
        return Integer.valueOf(value.toString());
    }

    private Integer buildPagerSize(ApiParams apiParams) {
        Object value = parseService.buildParamItem(apiParams,apiPager.getPageSizeVarName());
        if (StringUtils.isEmpty(value)){
            apiParams.putParam(apiPager.getPageSizeVarName(),apiPager.getPageSizeDefaultValue());
            return apiPager.getPageSizeDefaultValue();
        }
        return Integer.valueOf(value.toString());
    }

    public String buildPattern(HttpServletRequest request) {
        Set<RequestMappingInfo> infos = requestMappingHandlerMapping.getHandlerMethods().keySet();
        RequestMappingInfo currInfo = null;
        for (RequestMappingInfo info : infos){
            if ((currInfo = info.getMatchingCondition(request)) != null){
                break;
            }
        }
        return currInfo.getPatternsCondition().getPatterns().iterator().next();
    }

    /**
     * 注册mapping
     * @param apiInfo
     */
    private void registerMappingForApiInfo(ApiInfo apiInfo){
        if (ApiType.Code.name().equals(apiInfo.getType())){
            return;
        }
        if (StringUtils.isEmpty(apiInfo.getPath()) || apiInfo.getPath().startsWith("TEMP-")){
            return;
        }
        String pattern = apiInfo.getPath().replaceAll("/+","/");
        log.debug("register mapping [{}]{}",apiInfo.getMethod(),pattern);
        PatternsRequestCondition patternsRequestCondition = new PatternsRequestCondition(pattern);
        RequestMethodsRequestCondition methodsRequestCondition = new RequestMethodsRequestCondition(RequestMethod.valueOf(apiInfo.getMethod()));
        RequestMappingInfo mappingInfo = new RequestMappingInfo(patternsRequestCondition,methodsRequestCondition,null,null,null,null,null);
        Method targetMethod = ReflectionUtils.findMethod(this.getClass(), "execute",Map.class,Map.class,Map.class);
        requestMappingHandlerMapping.registerMapping(mappingInfo,this.getClass().getSimpleName(), targetMethod);
    }

    /**
     * 取消注册mapping
     * @param apiInfo
     */
    private void unregisterMappingForApiInfo(ApiInfo apiInfo){
        if (ApiType.Code.name().equals(apiInfo.getType())){
            return;
        }
        if (StringUtils.isEmpty(apiInfo.getPath()) || apiInfo.getScript().startsWith("TEMP-")){
            return;
        }
        log.debug("unregister mapping [{}]{}",apiInfo.getMethod(),apiInfo.getPath());
        PatternsRequestCondition patternsRequestCondition = new PatternsRequestCondition(apiInfo.getPath());
        RequestMethodsRequestCondition methodsRequestCondition = new RequestMethodsRequestCondition(RequestMethod.valueOf(apiInfo.getMethod()));
        RequestMappingInfo mappingInfo = new RequestMappingInfo(patternsRequestCondition,methodsRequestCondition,null,null,null,null,null);
        requestMappingHandlerMapping.unregisterMapping(mappingInfo);
    }

    public Collection<ApiInfo> getPathList(){
        return this.cacheApiInfo.values().stream().sorted(Comparator.comparing(ApiInfo::getComment).thenComparing(ApiInfo::getPath)).collect(Collectors.toList());
    }


    public void saveOrUpdateApiInfo(ApiInfo apiInfo) throws IOException {

        if (exists(apiInfo)){
            throw new IllegalArgumentException(buildApiInfoKey(apiInfo)+" already exist");
        }

        apiInfo.setUpdateTime(new Date());
        if (apiInfo.getId() == null){
            apiInfo.setType(ApiType.Sql.name());
            apiInfo.setCreateTime(new Date());
            apiInfo.setService(service);
            ApiParams apiParams = ApiParams.builder().param(apiInfo.toMap()).build();
            StringBuilder script = new StringBuilder(dataSourceManager.saveApiInfoScript());
            parseService.buildParams(script,apiParams);
            dataSourceManager.insert(script,ApiInfo.builder().datasource(dataSourceManager.getStoreApiKey()).build(),null);
        }else{
            ApiInfo dbInfo = this.cacheApiInfo.values().stream().filter(item->item.getId().equals(apiInfo.getId())).findFirst().orElse(null);

            ApiParams apiParams = ApiParams.builder().param(apiInfo.toMap()).build();
            StringBuilder script = new StringBuilder(dataSourceManager.updateApiInfoScript());
            parseService.buildParams(script,apiParams);
            dataSourceManager.update(script,ApiInfo.builder().datasource(dataSourceManager.getStoreApiKey()).build(),null);

            //取消mapping注册
            unregisterMappingForApiInfo(dbInfo);

            //清理缓存
            this.cacheApiInfo.remove(buildApiInfoKey(dbInfo));
        }

        ApiInfo dbInfo = getDbInfo(apiInfo);

        //入缓存
        this.cacheApiInfo.put(buildApiInfoKey(dbInfo),dbInfo);

        //注册mapping
        this.registerMappingForApiInfo(dbInfo);
    }

    public ApiInfo getDbInfo(ApiInfo apiInfo) throws IOException {
        ApiParams apiParams = ApiParams.builder().param(apiInfo.toMap()).build();
        StringBuilder script = new StringBuilder(dataSourceManager.getApiInfoScript());
        parseService.buildParams(script,apiParams);

        List<Map<String,Object>> apiInfoMap = dataSourceManager.find(script,ApiInfo.builder().datasource(dataSourceManager.getStoreApiKey()).build(),null);
        return objectMapper.readValue(objectMapper.writeValueAsBytes(apiInfoMap.get(0)),ApiInfo.class);
    }

    private boolean exists(ApiInfo apiInfo) {
        ApiInfo dbInfo = this.cacheApiInfo.values().stream().filter(item->item.getPath().equals(apiInfo.getPath()) && (item.getMethod().equals("All") || item.getMethod().equals(apiInfo.getMethod()))).findFirst().orElse(null);
        if (dbInfo == null || (apiInfo.getId() != null && apiInfo.getId().equals(dbInfo.getId()))){
            return false;
        }
        return true;
    }

    public void deleteApiInfo(ApiInfo apiInfo) {

        ApiInfo dbInfo = this.cacheApiInfo.values().stream().filter(item->item.getId().equals(apiInfo.getId())).findFirst().orElse(null);
        if (dbInfo == null){
            return;
        }

        ApiParams apiParams = ApiParams.builder()
                .param(apiInfo.toMap())
                .build();

        //清数据库
        StringBuilder script = new StringBuilder(dataSourceManager.deleteApiInfoScript());
        parseService.buildParams(script,apiParams);
        dataSourceManager.remove(script,ApiInfo.builder().datasource(dataSourceManager.getStoreApiKey()).build(),null);

        //清缓存
        this.cacheApiInfo.remove(buildApiInfoKey(dbInfo));

        //取消mapping注册
        unregisterMappingForApiInfo(dbInfo);
    }

    /**
     * 获取已注册的API地址
     * @return
     */
    public List<ApiInfo> getPathListForCode(){
        Map<RequestMappingInfo, HandlerMethod> map = requestMappingHandlerMapping.getHandlerMethods();
        List<ApiInfo> result = new ArrayList<>(map.size());
        for (RequestMappingInfo info : map.keySet()) {
            String group = map.get(info).getBeanType().getSimpleName();
            for(String path : info.getPatternsCondition().getPatterns()){

                String blankPath = blankList.stream().filter(item->path.startsWith(item)).findFirst().orElse(null);
                if (!StringUtils.isEmpty(blankPath)){
                    continue;
                }

                Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                if (methods.isEmpty()){
                    result.add(ApiInfo.builder()
                            .path(path)
                            .method("All")
                            .type(ApiType.Code.name())
                            .service(service)
                            .group(group)
                            .editor("admin")
                            .comment("")
                            .datasource("")
                            .script("")
                            .options("")
                            .build());
                }else{
                    for (RequestMethod method : methods){
                        result.add(ApiInfo.builder()
                                .path(path)
                                .method(method.name())
                                .type(ApiType.Code.name())
                                .service(service)
                                .group(group)
                                .editor("admin")
                                .comment("")
                                .datasource("")
                                .script("")
                                .options("")
                                .build());
                    }
                }

            }
        }
        return result;
    }

    public Set<String> getGroupNameList() {
        return this.cacheApiInfo.values().stream().map(ApiInfo::getGroup).collect(Collectors.toSet());
    }

    public Set<String> getApiNameList(String group) {
        return this.cacheApiInfo.values().stream().filter(item->group.equals(item.getGroup()))
                .map(item->StringUtils.isEmpty(item.getComment())?item.getPath():item.getComment()).collect(Collectors.toSet());
    }

    public void renameGroup(RenameGroupReq renameGroupReq) {
        List<ApiInfo> apiInfos = this.cacheApiInfo.values().stream().filter(item->item.getGroup().equals(renameGroupReq.getOldGroup())).collect(Collectors.toList());
        for (ApiInfo apiInfo : apiInfos){
            apiInfo.setGroup(renameGroupReq.getNewGroup());
            StringBuilder script = new StringBuilder(dataSourceManager.updateApiInfoScript());
            parseService.buildParams(script,ApiParams.builder().param(apiInfo.toMap()).build());
            dataSourceManager.update(script,ApiInfo.builder().datasource(dataSourceManager.getStoreApiKey()).build(),null);
        }

    }

    public void saveExample(ApiExample apiExample) {
        StringBuilder script = new StringBuilder(dataSourceManager.saveApiExampleScript());
        parseService.buildParams(script,ApiParams.builder().param(apiExample.toMap()).build());
        dataSourceManager.insert(script,ApiInfo.builder().datasource(dataSourceManager.getStoreApiKey()).build(),null);
    }

    public List<Map<String,Object>> lastApiExample(String apiInfoId, Integer limit) {
        ApiParams apiParams = new ApiParams();
        apiParams.putParam("apiInfoId",apiInfoId);
        apiParams.putParam("limit",limit);
        StringBuilder script = new StringBuilder(dataSourceManager.lastApiExampleScript());
        parseService.buildParams(script,apiParams);
        return dataSourceManager.find(script,ApiInfo.builder().datasource(dataSourceManager.getStoreApiKey()).build(),null);
    }

    public void deleteExampleList(ArrayList<ApiExample> apiExampleList) {
        StringBuilder script = new StringBuilder(dataSourceManager.deleteExampleScript());
        parseService.buildParams(script,new ApiParams().putParam("ids",apiExampleList.stream().map(ApiExample::getId).collect(Collectors.toSet())));
        dataSourceManager.remove(script,ApiInfo.builder().datasource(dataSourceManager.getStoreApiKey()).build(),null);
    }

    public void addInterceptor(ApiInfoInterceptor interceptor){
        if (this.interceptors == null){
            this.interceptors = new ArrayList<>();
        }
        this.interceptors.add(interceptor);
    }
}