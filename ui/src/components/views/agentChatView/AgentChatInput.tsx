import React, { useState } from "react";
import { Select, Space, Typography } from "antd";
import { Sender } from "@ant-design/x";
import type { PlanningMode } from "../../../types";

interface AgentChatInputProps {
  onSend: (message: string) => void | Promise<void>;
  loading?: boolean;
  planningMode: PlanningMode;
  onPlanningModeChange: (mode: PlanningMode) => void;
}

const planningModeOptions = [
  { value: "AUTO", label: "自动规划" },
  { value: "REQUIRED", label: "必须规划" },
  { value: "DISABLED", label: "关闭规划" },
] satisfies Array<{ value: PlanningMode; label: string }>;

const AgentChatInput: React.FC<AgentChatInputProps> = ({
  onSend,
  loading = false,
  planningMode,
  onPlanningModeChange,
}) => {
  const [message, setMessage] = useState("");

  return (
    <Space direction="vertical" size={8} className="w-full">
      <div className="flex items-center gap-2 text-xs text-gray-500">
        <Typography.Text type="secondary">执行模式</Typography.Text>
        <Select
          size="small"
          value={planningMode}
          options={planningModeOptions}
          onChange={(value: PlanningMode) => onPlanningModeChange(value)}
          disabled={loading}
          style={{ width: 112 }}
          aria-label="执行模式"
        />
        <Typography.Text type="secondary">
          规划事件会在对话区实时展示
        </Typography.Text>
      </div>
      <Sender
        onSubmit={() => {
          const trimmedMessage = message.trim();
          if (!trimmedMessage || loading) {
            return;
          }
          void onSend(trimmedMessage);
          setMessage("");
        }}
        loading={loading}
        placeholder="输入消息..."
        value={message}
        onChange={setMessage}
      />
    </Space>
  );
};

export default AgentChatInput;
