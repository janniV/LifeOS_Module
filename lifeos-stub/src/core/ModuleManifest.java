package core;

import java.util.List;

public class ModuleManifest {
    private String moduleId;
    private String moduleName;
    private String type;
    private String executable;
    private String icon;
    private List<String> aiTools;

    public String getModuleId() { return moduleId; }
    public void setModuleId(String moduleId) { this.moduleId = moduleId; }
    public String getModuleName() { return moduleName; }
    public void setModuleName(String moduleName) { this.moduleName = moduleName; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getExecutable() { return executable; }
    public void setExecutable(String executable) { this.executable = executable; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public List<String> getAiTools() { return aiTools; }
    public void setAiTools(List<String> aiTools) { this.aiTools = aiTools; }
}
