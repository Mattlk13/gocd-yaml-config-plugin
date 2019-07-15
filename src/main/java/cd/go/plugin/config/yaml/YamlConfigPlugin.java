package cd.go.plugin.config.yaml;

import cd.go.plugin.config.yaml.transforms.RootTransform;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.thoughtworks.go.plugin.api.GoApplicationAccessor;
import com.thoughtworks.go.plugin.api.GoPlugin;
import com.thoughtworks.go.plugin.api.GoPluginIdentifier;
import com.thoughtworks.go.plugin.api.annotation.Extension;
import com.thoughtworks.go.plugin.api.exceptions.UnhandledRequestTypeException;
import com.thoughtworks.go.plugin.api.logging.Logger;
import com.thoughtworks.go.plugin.api.request.GoApiRequest;
import com.thoughtworks.go.plugin.api.request.GoPluginApiRequest;
import com.thoughtworks.go.plugin.api.response.DefaultGoPluginApiResponse;
import com.thoughtworks.go.plugin.api.response.GoApiResponse;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

import static cd.go.plugin.config.yaml.PluginSettings.DEFAULT_FILE_PATTERN;
import static cd.go.plugin.config.yaml.PluginSettings.PLUGIN_SETTINGS_FILE_PATTERN;
import static com.thoughtworks.go.plugin.api.response.DefaultGoPluginApiResponse.*;
import static java.lang.String.format;

@Extension
public class YamlConfigPlugin implements GoPlugin, ConfigRepoMessages {
    private static final String DISPLAY_NAME_FILE_PATTERN = "Go YAML files pattern";
    private static final String PLUGIN_ID = "yaml.config.plugin";
    private static Logger LOGGER = Logger.getLoggerFor(YamlConfigPlugin.class);

    private final Gson gson = new Gson();
    private GoApplicationAccessor goApplicationAccessor;
    private PluginSettings settings;

    @Override
    public void initializeGoApplicationAccessor(GoApplicationAccessor goApplicationAccessor) {
        this.goApplicationAccessor = goApplicationAccessor;
    }

    @Override
    public GoPluginIdentifier pluginIdentifier() {
        return getGoPluginIdentifier();
    }

    private GoPluginIdentifier getGoPluginIdentifier() {
        return new GoPluginIdentifier("configrepo", Arrays.asList("1.0", "2.0", "3.0"));
    }

    @Override
    public GoPluginApiResponse handle(GoPluginApiRequest request) throws UnhandledRequestTypeException {
        String requestName = request.requestName();

        switch (requestName) {
            case REQ_PLUGIN_SETTINGS_GET_CONFIGURATION:
                return handleGetPluginSettingsConfiguration();
            case REQ_CONFIG_FILES:
                return handleGetConfigFiles(request);
            case REQ_PLUGIN_SETTINGS_GET_VIEW:
                try {
                    return handleGetPluginSettingsView();
                } catch (IOException e) {
                    return error(gson.toJson(format("Failed to find template: %s", e.getMessage())));
                }
            case REQ_PLUGIN_SETTINGS_VALIDATE_CONFIGURATION:
                return handleValidatePluginSettingsConfiguration();
            case REQ_PARSE_CONTENT:
                return handleParseContentRequest(request);
            case REQ_PARSE_DIRECTORY:
                ensureConfigured();
                return handleParseDirectoryRequest(request);
            case REQ_PIPELINE_EXPORT:
                return handlePipelineExportRequest(request);
            case REQ_GET_CAPABILITIES:
                return success(gson.toJson(new Capabilities()));
            case REQ_PLUGIN_SETTINGS_CHANGED:
                configurePlugin(PluginSettings.fromJson(request.requestBody()));
                return new DefaultGoPluginApiResponse(SUCCESS_RESPONSE_CODE, "");
            case REQ_GET_ICON:
                return handleGetIconRequest();
            default:
                throw new UnhandledRequestTypeException(requestName);
        }
    }

    private GoPluginApiResponse handleGetIconRequest() {
        try {
            JsonObject jsonObject = new JsonObject();
            byte[] contents = IOUtils.toByteArray(getClass().getResourceAsStream("/yaml.svg"));

            jsonObject.addProperty("content_type", "image/svg+xml");
            jsonObject.addProperty("data", Base64.getEncoder().encodeToString(contents));
            return success(gson.toJson(jsonObject));
        } catch (IOException e) {
            return error(e.getMessage());
        }
    }

    String getFilePattern() {
        if (null != settings && !isBlank(settings.getFilePattern())) {
            return settings.getFilePattern();
        }
        return DEFAULT_FILE_PATTERN;
    }

    /**
     * fetches plugin settings if we haven't yet
     */
    private void ensureConfigured() {
        if (null == settings) {
            settings = fetchPluginSettings();
        }
    }

    private GoPluginApiResponse handleGetConfigFiles(GoPluginApiRequest request) {
        return handlingErrors(() -> {
            ParsedRequest parsed = ParsedRequest.parse(request);
            File baseDir = new File(parsed.getStringParam("directory"));
            String pattern = parsed.getConfigurationKey(PLUGIN_SETTINGS_FILE_PATTERN);

            if (isBlank(pattern)) {
                pattern = getFilePattern();
            }

            String[] files = new AntDirectoryScanner().getFilesMatchingPattern(baseDir, pattern);
            Map<String, String[]> result = new HashMap<>();
            result.put("files", files);

            return success(gson.toJson(result));
        });
    }

    private GoPluginApiResponse handleParseContentRequest(GoPluginApiRequest request) {
        return handlingErrors(() -> {
            ParsedRequest parsed = ParsedRequest.parse(request);

            YamlConfigParser parser = new YamlConfigParser();
            Map<String, String> contents = parsed.getParam("contents");
            JsonConfigCollection result = new JsonConfigCollection();
            contents.forEach((filename, content) -> {
                parser.parseStream(result, new ByteArrayInputStream(content.getBytes()), filename);
            });
            result.updateTargetVersionFromFiles();

            return success(gson.toJson(result.getJsonObject()));
        });
    }

    private GoPluginApiResponse handlePipelineExportRequest(GoPluginApiRequest request) {
        return handlingErrors(() -> {
            ParsedRequest parsed = ParsedRequest.parse(request);

            Map<String, Object> pipeline = parsed.getParam("pipeline");
            String name = (String) pipeline.get("name");

            Map<String, String> responseMap = Collections.singletonMap("pipeline", new RootTransform().inverseTransformPipeline(pipeline));
            DefaultGoPluginApiResponse response = success(gson.toJson(responseMap));

            response.addResponseHeader("Content-Type", "application/x-yaml; charset=utf-8");
            response.addResponseHeader("X-Export-Filename", name + ".gocd.yaml");
            return response;
        });
    }

    private GoPluginApiResponse handleParseDirectoryRequest(GoPluginApiRequest request) {
        return handlingErrors(() -> {
            ParsedRequest parsed = ParsedRequest.parse(request);
            File baseDir = new File(parsed.getStringParam("directory"));
            String pattern = parsed.getConfigurationKey(PLUGIN_SETTINGS_FILE_PATTERN);

            if (isBlank(pattern)) {
                pattern = getFilePattern();
            }

            YamlConfigParser parser = new YamlConfigParser();

            String[] files = new AntDirectoryScanner().getFilesMatchingPattern(baseDir, pattern);

            JsonConfigCollection config = parser.parseFiles(baseDir, files);
            config.updateTargetVersionFromFiles();

            return success(gson.toJson(config.getJsonObject()));
        });
    }

    private static boolean isBlank(String pattern) {
        return pattern == null || pattern.isEmpty();
    }

    private GoPluginApiResponse handleGetPluginSettingsView() throws IOException {
        Map<String, Object> response = new HashMap<String, Object>();
        response.put("template", IOUtils.toString(getClass().getResourceAsStream("/plugin-settings.template.html"), "UTF-8"));
        return success(gson.toJson(response));
    }

    private GoPluginApiResponse handleValidatePluginSettingsConfiguration() {
        List<Map<String, Object>> response = new ArrayList<>();
        return success(gson.toJson(response));
    }

    private GoPluginApiResponse handleGetPluginSettingsConfiguration() {
        Map<String, Object> response = new HashMap<>();
        response.put(PLUGIN_SETTINGS_FILE_PATTERN, createField(DISPLAY_NAME_FILE_PATTERN, DEFAULT_FILE_PATTERN, false, false, "0"));
        return success(gson.toJson(response));
    }

    private Map<String, Object> createField(String displayName, String defaultValue, boolean isRequired, boolean isSecure, String displayOrder) {
        Map<String, Object> fieldProperties = new HashMap<String, Object>();
        fieldProperties.put("display-name", displayName);
        fieldProperties.put("default-value", defaultValue);
        fieldProperties.put("required", isRequired);
        fieldProperties.put("secure", isSecure);
        fieldProperties.put("display-order", displayOrder);
        return fieldProperties;
    }

    private GoPluginApiResponse handlingErrors(Supplier<GoPluginApiResponse> exec) {
        try {
            return exec.get();
        } catch (ParsedRequest.RequestParseException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Unexpected error occurred in YAML configuration plugin.", e);
            JsonConfigCollection config = new JsonConfigCollection();
            config.addError(new PluginError(e.toString(), "YAML config plugin"));
            return error(gson.toJson(config.getJsonObject()));
        }
    }

    private PluginSettings fetchPluginSettings() {
        Map<String, Object> requestMap = new HashMap<String, Object>();
        requestMap.put("plugin-id", PLUGIN_ID);
        GoApiResponse response = goApplicationAccessor.submit(createGoApiRequest(REQ_GET_PLUGIN_SETTINGS, JSONUtils.toJSON(requestMap)));

        if (response.responseBody() == null || response.responseBody().trim().isEmpty()) {
            return new PluginSettings();
        }

        return PluginSettings.fromJson(response.responseBody());
    }

    private void configurePlugin(PluginSettings settings) {
        this.settings = settings;
    }

    private GoApiRequest createGoApiRequest(final String api, final String responseBody) {
        return new GoApiRequest() {
            @Override
            public String api() {
                return api;
            }

            @Override
            public String apiVersion() {
                return "1.0";
            }

            @Override
            public GoPluginIdentifier pluginIdentifier() {
                return getGoPluginIdentifier();
            }

            @Override
            public Map<String, String> requestParameters() {
                return null;
            }

            @Override
            public Map<String, String> requestHeaders() {
                return null;
            }

            @Override
            public String requestBody() {
                return responseBody;
            }
        };
    }
}
