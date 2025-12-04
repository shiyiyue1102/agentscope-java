package io.agentscope.core.memory.autocontext;

import com.alibaba.dashscope.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.message.Msg;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class LocalFileContextOffLoader implements ContextOffLoader {

    String baseDir;

    public LocalFileContextOffLoader(String baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public void offload(String uuid, List<Msg> messages) {
        try {
            Path file = Paths.get(baseDir, uuid);
            if (file.toFile().exists()) {
                file.toFile().delete();
            }
            Files.write(file, JsonUtils.toJson(messages).getBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Path getPath(String uuid) {
        return Paths.get(baseDir, uuid);
    }

    @Override
    public List<Msg> reload(String uuid) {
        try {
            Path file = getPath(uuid);
            if (!file.toFile().exists()) {
                return List.of();
            }
            return JsonUtils.fromJson(
                    Files.readString(file), new TypeReference<List<Msg>>() {}.getType());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void clear(String uuid) {
        Path file = getPath(uuid);
        if (file.toFile().exists()) {
            file.toFile().delete();
        }
    }
}
