package com.aifashionvault.avatar;

public interface SemanticInspector {

	SemanticInspection inspect(byte[] imageBytes) throws SemanticInspectionUnavailableException;
}
