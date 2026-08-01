package com.enterprise.ai.agent.agent;

import com.enterprise.ai.agent.model.AgentRequest;
import com.enterprise.ai.agent.model.AgentResponse;

public interface Agent {
    AgentResponse execute(AgentRequest request);
}
