/*
 * The MIT License
 *
 * Copyright 2017 Intuit Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.intuit.karate;

import com.intuit.karate.cucumber.FeatureWrapper;
import com.intuit.karate.http.HttpRequest;
import com.intuit.karate.http.HttpUtils;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import jdk.nashorn.api.scripting.ScriptObjectMirror;
import org.w3c.dom.Document;

/**
 *
 * @author pthomas3
 */
public class ScriptBridge {
    
    private static final Object GLOBALS_LOCK = new Object();
    private static final Map<String, Object> GLOBALS = new HashMap();
    
    public final ScriptContext context;
    
    public ScriptBridge(ScriptContext context) {
        this.context = context;       
    }

    public ScriptContext getContext() {
        return context;
    }        
    
    public void configure(String key, Object o) {
        context.configure(key, new ScriptValue(o));
    }
    
    public Object read(String fileName) {
        ScriptValue sv = FileUtils.readFile(fileName, context);
        // json should behave like json within js / function
        return sv.isJsonLike() ? sv.getAfterConvertingFromJsonOrXmlIfNeeded() : sv.getValue();
    }
    
    public String pretty(Object o) {
        ScriptValue sv = new ScriptValue(o);
        return sv.getAsPrettyString();
    }
    
    public String prettyXml(Object o) {
        ScriptValue sv = new ScriptValue(o);
        if (sv.isMapLike()) {
            Document doc = XmlUtils.fromMap(sv.getAsMap());
            return XmlUtils.toString(doc, true);
        } else {
            String xml = sv.getAsString();
            Document doc = XmlUtils.toXmlDoc(xml);
            return XmlUtils.toString(doc, true);
        }
    }
    
    public void set(String name, Object o) {
        context.vars.put(name, o);
    }
    
    // this makes sense for xml / xpath manipulation from within js
    public void set(String name, String path, String expr) {
        Script.setValueByPath(name, path, expr, context);
    }
    
    // this makes sense for xml / xpath manipulation from within js
    public void remove(String name, String path) {
        Script.removeValueByPath(name, path, context);
    }    
    
    public Object get(String exp) {
        ScriptValue sv;
        try {
            sv = Script.evalKarateExpression(exp, context); // even json path expressions will work
        } catch (Exception e) {
            context.logger.warn("karate.get failed for expression: '{}': {}", exp, e.getMessage());
            return null;
        }
        if (sv != null) {
            return sv.getAfterConvertingFromJsonOrXmlIfNeeded();
        } else {
            return null;
        }
    }
    
    public Object jsonPath(Object o, String exp) {
        DocumentContext doc = JsonPath.parse(o);
        return doc.read(exp);
    }
    
    public Object toBean(Object o, String className) {
        ScriptValue sv = new ScriptValue(o);
        DocumentContext doc = Script.toJsonDoc(sv, context);
        return JsonUtils.fromJson(doc.jsonString(), className);
    }   
    
    public Object call(String fileName) {
        return call(fileName, null);
    }

    public Object call(String fileName, Object arg) {
        ScriptValue sv = FileUtils.readFile(fileName, context);
        switch(sv.getType()) {
            case FEATURE_WRAPPER:
                FeatureWrapper feature = sv.getValue(FeatureWrapper.class);
                return Script.evalFeatureCall(feature, arg, context, false).getValue();
            case JS_FUNCTION:
                ScriptObjectMirror som = sv.getValue(ScriptObjectMirror.class);
                return Script.evalFunctionCall(som, arg, context).getValue();
            default:
                context.logger.warn("not a js function or feature file: {} - {}", fileName, sv);
                return null;
        }        
    }
    
    public Object callSingle(String fileName) {
        return callSingle(fileName, null);
    }
    
    public Object callSingle(String fileName, Object arg) {
        if (GLOBALS.containsKey(fileName)) {
            context.logger.trace("callSingle cache hit: {}", fileName);
            return GLOBALS.get(fileName);
        }
        long startTime = System.currentTimeMillis();
        context.logger.trace("callSingle waiting for lock: {}", fileName);
        synchronized (GLOBALS_LOCK) { // lock
            if (GLOBALS.containsKey(fileName)) { // retry
                long endTime = System.currentTimeMillis() - startTime;
                context.logger.warn("this thread waited {} milliseconds for callSingle lock: {}", endTime, fileName);
                return GLOBALS.get(fileName);
            } 
            // this thread is the 'winner'
            context.logger.info(">> lock acquired, begin callSingle: {}", fileName);
            Object result = call(fileName, arg);
            GLOBALS.put(fileName, result);
            context.logger.info("<< lock released, end callSingle: {}", fileName);
            return result;
        }        
    }
    
    public HttpRequest getPrevRequest() {
        return context.prevRequest;
    }
    
    public Object eval(String exp) {
        ScriptValue sv = Script.evalJsExpression(exp, context);
        return sv.getValue();
    }
    
    public List<String> getTags() {
        return context.tags;
    }
    
    public Map<String, List<String>> getTagValues() {
        return context.tagValues;
    }    
    
    public Map<String, Object> getInfo() {
        DocumentContext doc = JsonUtils.toJsonDoc(context.scenarioInfo);
        return doc.read("$");        
    }
    
    public boolean pathMatches(String path) {
        String uri = (String) get(ScriptValueMap.VAR_REQUEST_URI);
        Map<String, String> map = HttpUtils.parseUriPattern(path, uri);
        set(ScriptBindings.PATH_PARAMS, map);
        return map != null;
    }
    
    private boolean headerValueContains(String name, String test) {
        Map<String, List<String>> headers = (Map) get(ScriptValueMap.VAR_REQUEST_HEADERS);
        if (headers == null) {
            return false;
        }
        List<String> list = headers.get(name);
        if (list == null) {
            return false;
        }
        for (String s: list) {
            if (s != null && s.contains(test)) {
                return true;
            }
        }
        return false;
    }
    
    public boolean typeContains(String test) {
        return headerValueContains(HttpUtils.CONTENT_TYPE, test);
    } 
    
    public boolean acceptContains(String test) {
        return headerValueContains(HttpUtils.ACCEPT, test);        
    }
    
    public String getEnv() {
        return context.env.env;
    }
    
    public Properties getProperties() {
        return System.getProperties();
    }
    
    public void log(Object ... objects) {
        if (context.isPrintEnabled() && context.logger.isInfoEnabled()) {
            context.logger.info("{}", new LogWrapper(objects));
        }
    }        
    
    // make sure toString() is lazy
    static class LogWrapper {
        
        private final Object[] objects;
        
        LogWrapper(Object ... objects) {
            this.objects = objects;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (Object o : objects) {
                sb.append(o).append(' ');                
            }
            return sb.toString();
        }
                
    }
    
}
