import React, { useMemo, useState } from "react";
import { Card, message as antdMessage, Space, Typography, Select } from "antd";
import {
  BulbOutlined,
  MessageOutlined,
  RobotOutlined,
  DownOutlined,
} from "@ant-design/icons";
import { useNavigate } from "react-router-dom";
import {
  type AgentVO,
  createChatSession,
} from "../../../api/api.ts";
import { getAgentEmoji } from "../../../utils";
import { useChatSessions } from "../../../hooks/useChatSessions.ts";
import AgentChatInput from "./AgentChatInput.tsx";
import type { PlanningMode } from "../../../types";

const { Title, Text } = Typography;

interface EmptyAgentChatViewProps {
  agents: AgentVO[];
}

const EmptyAgentChatView: React.FC<EmptyAgentChatViewProps> = ({ agents }) => {
  const [selectedAgentId, setSelectedAgentId] = useState<string | null>(null);
  const [planningMode, setPlanningMode] = useState<PlanningMode>("AUTO");
  const [sending, setSending] = useState(false);

  const navigate = useNavigate();
  const { refreshChatSessions } = useChatSessions();

  // 为每个 agent 生成 emoji
  const agentsWithEmoji = useMemo(() => {
    return agents.map((agent) => ({
      ...agent,
      emoji: getAgentEmoji(agent.id),
    }));
  }, [agents]);

  // 计算实际选中的 agent ID（如果用户没有选择，则使用默认的第一个）
  const effectiveAgentId = useMemo(() => {
    if (selectedAgentId) {
      return selectedAgentId;
    }
    return agents.length > 0 ? agents[0].id : null;
  }, [selectedAgentId, agents]);

  const handleInitialSend = async (value: string) => {
    const content = value.trim();
    if (!content || sending) {
      return;
    }
    if (!effectiveAgentId) {
      antdMessage.warning("请先创建一个智能体助手");
      return;
    }

    setSending(true);
    try {
      const sessionResponse = await createChatSession({
        agentId: effectiveAgentId,
        title: content.slice(0, 20),
      });
      await refreshChatSessions();

      // 先进入会话页，由 AgentChatView 在 SSE 建立后提交一次性消息，避免错过规划事件。
      navigate(`/chat/${sessionResponse.chatSessionId}`, {
        state: { initialMessage: content, planningMode },
      });
    } catch (error) {
      console.error("创建聊天会话失败:", error);
      antdMessage.error("创建聊天会话或发送消息失败，请重试");
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="flex flex-col h-full">
      {/* Agent 选择器 - 顶部 */}
      {agents.length > 0 && (
        <div className="border-b border-gray-200 bg-white px-4 py-3">
          <div className="flex items-center justify-start">
            <Select
              value={effectiveAgentId}
              onChange={(value) => setSelectedAgentId(value)}
              style={{ width: 200 }}
              className="agent-selector"
              suffixIcon={<DownOutlined className="text-gray-400" />}
              placeholder="选择智能体助手"
              optionRender={(option) => (
                <div className="flex items-center gap-2">
                  <span className="text-lg">
                    {agentsWithEmoji.find((a) => a.id === option.value)?.emoji}
                  </span>
                  <span className="text-sm">{option.label}</span>
                </div>
              )}
              options={agentsWithEmoji.map((agent) => ({
                value: agent.id,
                label: agent.name,
              }))}
            />
          </div>
        </div>
      )}
      <div className="flex-1 flex items-center justify-center p-6">
        <div className="max-w-2xl w-full space-y-6">
          <div className="text-center mb-8">
            <Title level={2} className="mb-2">
              开始新的对话
            </Title>
            <Text type="secondary" className="text-base">
              选择一个智能体助手开始聊天，或直接发送消息创建新会话
            </Text>
          </div>
          <Space orientation="vertical" size="large" className="w-full">
            <Card
              hoverable
              className="cursor-pointer transition-all hover:shadow-lg"
            >
              <Space size="middle">
                <div className="w-12 h-12 rounded-full bg-gradient-to-br from-blue-400 to-purple-400 flex items-center justify-center">
                  <RobotOutlined className="text-white text-xl" />
                </div>
                <div>
                  <Title level={5} className="mb-1">
                    智能对话
                  </Title>
                  <Text type="secondary">
                    与 AI 助手进行智能对话，获取帮助和建议
                  </Text>
                </div>
              </Space>
            </Card>

            <Card
              hoverable
              className="cursor-pointer transition-all hover:shadow-lg"
            >
              <Space size="middle">
                <div className="w-12 h-12 rounded-full bg-gradient-to-br from-green-400 to-teal-400 flex items-center justify-center">
                  <BulbOutlined className="text-white text-xl" />
                </div>
                <div>
                  <Title level={5} className="mb-1">
                    知识问答
                  </Title>
                  <Text type="secondary">
                    基于知识库进行问答，获取准确的信息
                  </Text>
                </div>
              </Space>
            </Card>

            <Card
              hoverable
              className="cursor-pointer transition-all hover:shadow-lg"
            >
              <Space size="middle">
                <div className="w-12 h-12 rounded-full bg-gradient-to-br from-orange-400 to-red-400 flex items-center justify-center">
                  <MessageOutlined className="text-white text-xl" />
                </div>
                <div>
                  <Title level={5} className="mb-1">
                    快速开始
                  </Title>
                  <Text type="secondary">
                    在下方输入框输入消息，立即开始对话
                  </Text>
                </div>
              </Space>
            </Card>
          </Space>
        </div>
      </div>
      <div className="border-t border-gray-200 bg-white">
        <div className="px-4 pb-4 pt-4">
          <AgentChatInput
            onSend={handleInitialSend}
            loading={sending}
            planningMode={planningMode}
            onPlanningModeChange={setPlanningMode}
          />
        </div>
      </div>
    </div>
  );
};

export default EmptyAgentChatView;
