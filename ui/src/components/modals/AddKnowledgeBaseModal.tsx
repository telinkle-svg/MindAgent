import React, { useEffect, useState } from "react";
import { Button, Input, Modal } from "antd";
import TextArea from "antd/es/input/TextArea";
import { SaveOutlined } from "@ant-design/icons";
import { type CreateKnowledgeBaseRequest, type UpdateKnowledgeBaseRequest } from "../../api/api.ts";
import type { KnowledgeBase } from "../../types";

interface AddKnowledgeBaseModalProps {
  open: boolean;
  onClose: () => void;
  createKnowledgeBaseHandle?: (
    request: CreateKnowledgeBaseRequest,
  ) => Promise<void>;
  updateKnowledgeBaseHandle?: (
    knowledgeBaseId: string,
    request: UpdateKnowledgeBaseRequest,
  ) => Promise<void>;
  editingKnowledgeBase?: KnowledgeBase | null;
}

const AddKnowledgeBaseModal: React.FC<AddKnowledgeBaseModalProps> = ({
  open,
  onClose,
  createKnowledgeBaseHandle,
  updateKnowledgeBaseHandle,
  editingKnowledgeBase,
}) => {
  const [formData, setFormData] = useState<CreateKnowledgeBaseRequest>({
    name: "",
    description: "",
  });
  
  const [createLoading, setCreateLoading] = useState(false);

  const isEditMode = !!editingKnowledgeBase;

  // 当编辑的知识库变化时，更新表单数据
  useEffect(() => {
    if (editingKnowledgeBase) {
      setFormData({
        name: editingKnowledgeBase.name,
        description: editingKnowledgeBase.description || "",
      });
    } else {
      // 重置表单
      setFormData({
        name: "",
        description: "",
      });
    }
  }, [editingKnowledgeBase, open]);

  const handleSubmit = async () => {
    if (!formData.name.trim()) {
      return;
    }
    setCreateLoading(true);

    try {
      if (isEditMode && editingKnowledgeBase && updateKnowledgeBaseHandle) {
        await updateKnowledgeBaseHandle(
          editingKnowledgeBase.knowledgeBaseId,
          formData,
        );
      } else if (createKnowledgeBaseHandle) {
        await createKnowledgeBaseHandle(formData);
      }
      // 重置表单
      setFormData({
        name: "",
        description: "",
      });
      onClose();
    } finally {
      setCreateLoading(false);
    }
  };

  const handleCancel = () => {
    // 重置表单
    setFormData({
      name: "",
      description: "",
    });
    onClose();
  };

  return (
    <Modal
      open={open}
      onCancel={handleCancel}
      title={isEditMode ? "编辑知识库" : "新建知识库"}
      footer={null}
      width={600}
      centered
    >
      <div className="py-4">
        <div className="mb-4">
          <label className="block text-gray-700 font-medium mb-2">
            名称 <span className="text-red-500">*</span>
          </label>
          <Input
            placeholder="请输入知识库名称"
            value={formData.name}
            onChange={(e) =>
              setFormData({ ...formData, name: e.target.value })
            }
            onPressEnter={handleSubmit}
          />
        </div>
        <div className="mb-6">
          <label className="block text-gray-700 font-medium mb-2">
            描述
          </label>
          <TextArea
            placeholder="请输入知识库描述（可选）"
            rows={4}
            value={formData.description}
            onChange={(e) =>
              setFormData({ ...formData, description: e.target.value })
            }
          />
        </div>
        <div className="flex justify-end gap-2">
          <Button onClick={handleCancel}>取消</Button>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={createLoading}
            onClick={handleSubmit}
            disabled={!formData.name.trim()}
          >
            {isEditMode ? "保存" : "创建"}
          </Button>
        </div>
      </div>
    </Modal>
  );
};

export default AddKnowledgeBaseModal;
