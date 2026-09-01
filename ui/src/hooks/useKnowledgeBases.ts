import { useCallback, useEffect, useState } from "react";
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

function convertKnowledgeBases(resp: GetKnowledgeBasesResponse): KnowledgeBase[] {
  return resp.knowledgeBases.map((kb) => ({
    knowledgeBaseId: kb.id,
    name: kb.name,
    description: kb.description || "",
  }));
}

export function useKnowledgeBases() {
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);

  const refreshKnowledgeBases = useCallback(async () => {
    const resp = await getKnowledgeBases();
    setKnowledgeBases(convertKnowledgeBases(resp));
  }, []);

  useEffect(() => {
    let disposed = false;
    void getKnowledgeBases()
      .then((resp) => {
        if (!disposed) {
          setKnowledgeBases(convertKnowledgeBases(resp));
        }
      })
      .catch(() => undefined);
    return () => {
      disposed = true;
    };
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
