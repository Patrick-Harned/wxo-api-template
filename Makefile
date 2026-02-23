# Location of the final output YAML
OUTPUT_YAML = agent/agent.yaml

# Temporary file to store orchestrate output
IMPORT_OUTPUT = agent/import_output.txt

# Temporary file to store extracted tool names
TOOLS_FILE = agent/tools.txt

# Default goal
all: $(OUTPUT_YAML)

# Step 1: Run orchestrate command and capture output
import:
	@echo "Running orchestrate tools import..."
	@orchestrate env activate epm
	@sbt openapi
	@orchestrate tools import -k openapi -f openapi.yaml --app-id epm-tool-oauth > $(IMPORT_OUTPUT) 2>&1
	@echo "Import output saved to $(IMPORT_OUTPUT)"

# Step 2: Extract tool names from orchestrate output
extract: import
	@echo "Extracting tool names..."
	@grep -o "'[^']*'" $(IMPORT_OUTPUT) | tr -d "'" | grep -v "^get$$" | sort -u > $(TOOLS_FILE)
	@echo "Tools extracted to $(TOOLS_FILE)"

# Step 3: Generate final YAML
$(OUTPUT_YAML): extract
	@echo "Generating $(OUTPUT_YAML)..."
	@echo "spec_version: v1" > $(OUTPUT_YAML)
	@echo "kind: native" >> $(OUTPUT_YAML)
	@echo "name: epm_test" >> $(OUTPUT_YAML)
	@echo "style: default" >> $(OUTPUT_YAML)
	@echo "hide_reasoning: False" >> $(OUTPUT_YAML)
	@echo "description: |" >> $(OUTPUT_YAML)
	@echo "    Can answer questions about IBM Procurement by querying the epm database." >> $(OUTPUT_YAML)
	@echo "instructions: Use the tools to answer the questions" >> $(OUTPUT_YAML)
	@echo "tools:" >> $(OUTPUT_YAML)
	@sed 's/^/  - /' $(TOOLS_FILE) >> $(OUTPUT_YAML)
	@echo "restrictions: editable" >> $(OUTPUT_YAML)
	@echo "✓ Generated $(OUTPUT_YAML)"

# Deploy the agent
deploy: $(OUTPUT_YAML)
	@echo "Deploying agent..."\
	@orchestrate agents import -f $(OUTPUT_YAML)
	@orchestrate agents deploy -n epm_test
	@echo "✓ Agent deployed"

# Cleanup temporary files
clean:
	@echo "Cleaning up..."
	@rm -f $(IMPORT_OUTPUT) $(TOOLS_FILE) $(OUTPUT_YAML)
	@echo "✓ Cleaned"

# Show help
help:
	@echo "Available targets:"
	@echo "  make          - Generate YAML from orchestrate output"
	@echo "  make deploy   - Generate YAML and deploy agent"
	@echo "  make clean    - Remove generated files"
	@echo "  make help     - Show this help"

.PHONY: all import extract deploy clean help

