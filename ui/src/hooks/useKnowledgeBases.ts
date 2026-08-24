import { useEffect, useState } from "react";
import {
  createKnowledgeBase,
  deleteKnowledgeBase,
  getKnowledgeBases,
  updateKnowledgeBase,
  type CreateKnowledgeBaseRequest,
  type GetKnowledgeBasesResponse,
  type UpdateKnowledgeBaseRequest,
} from "../api/api.ts";
import type { KnowledgeBase } from "../types";

export function useKnowledgeBases() {
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);

  const convert = (resp: GetKnowledgeBasesResponse): KnowledgeBase[] =>
    resp.knowledgeBases.map((kb) => ({
      knowledgeBaseId: kb.id,
      name: kb.name,
      description: kb.description || "",
    }));

  async function refreshKnowledgeBases() {
    const resp = await getKnowledgeBases();
    setKnowledgeBases(convert(resp));
  }

  useEffect(() => {
    refreshKnowledgeBases().then();
  }, []);

  async function createKnowledgeBaseHandle(
    request: CreateKnowledgeBaseRequest,
  ) {
    await createKnowledgeBase(request);
    await refreshKnowledgeBases();
  }

  async function updateKnowledgeBaseHandle(
    knowledgeBaseId: string,
    request: UpdateKnowledgeBaseRequest,
  ) {
    await updateKnowledgeBase(knowledgeBaseId, request);
    await refreshKnowledgeBases();
  }

  async function deleteKnowledgeBaseHandle(knowledgeBaseId: string) {
    await deleteKnowledgeBase(knowledgeBaseId);
    await refreshKnowledgeBases();
  }

  return {
    knowledgeBases,
    createKnowledgeBaseHandle,
    updateKnowledgeBaseHandle,
    deleteKnowledgeBaseHandle,
  };
}