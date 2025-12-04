package io.agentscope.core.memory.autocontext;

import io.agentscope.core.message.Msg;
import java.util.List;

public interface MessageStorage {

    void addMessage(Msg message);

    List<Msg> getMessages();

    void deleteMessage(int index);

    void clear();
}
