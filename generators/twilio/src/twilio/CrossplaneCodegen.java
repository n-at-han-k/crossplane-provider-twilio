package twilio;

import com.twilio.oai.PathUtils;
import com.twilio.oai.StringHelper;
import com.twilio.oai.TwilioTerraformGenerator;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.openapitools.codegen.CliOption;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.SupportingFile;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.OperationsMap;
import org.openapitools.codegen.utils.ModelUtils;

/**
 * A Crossplane provider from a Twilio OpenAPI document.
 *
 * This extends Twilio's OWN generator rather than upstream openapi-generator's,
 * which is the whole point: {@link TwilioTerraformGenerator} already decides
 * which operations are one resource, which of them is the create, the read, the
 * update and the delete, what the external name is, and which fields can only
 * be set after a create. Those were the hard parts in
 * crossplane-provider-rt and crossplane-provider-orangehrm, where nobody had
 * described the API and the generator had to infer all of it. Here the answers
 * are inherited, so a difference between this provider and
 * terraform-provider-twilio is structural rather than a bug waiting to be
 * found. See PARITY.md and TOOLCHAIN.md.
 *
 * What comes from the superclass, and is deliberately not restated here:
 *
 * <ul>
 *   <li><b>Resource grouping.</b> A resource is the path with its extension,
 *       its trailing path parameter and its parameter names removed, so
 *       {@code /Addresses.json} and {@code /Addresses/{Sid}.json} are one
 *       thing.</li>
 *   <li><b>The CRUD verbs</b>, from the {@code operationId} prefix rather than
 *       the HTTP method -- Twilio updates with a POST to the instance path, so
 *       the method cannot tell a create from an update.</li>
 *   <li><b>{@code x-resource-id}</b>, the fetch's last path parameter, which is
 *       the external name. It is not always {@code sid}: 55 of 207 instance
 *       reads answer something else.</li>
 *   <li><b>{@code x-update-after-create}</b>, for a field the create cannot
 *       set.</li>
 *   <li><b>{@code PathAccountSid}</b>. The superclass rewrites the
 *       {@code AccountSid} path parameter into an optional query parameter, so
 *       the account comes from the credentials and no manifest has to repeat
 *       it.</li>
 * </ul>
 *
 * What this class adds is the Crossplane shape: one Kind per resource, the
 * spec/status split, and the file layout.
 */
public class CrossplaneCodegen extends TwilioTerraformGenerator {

    public static final String PROVIDER_NAME = "providerName";
    public static final String GROUP_NAME = "groupName";
    // NOT "apiVersion": that key is DirectoryStructureService.API_VERSION,
    // holding Twilio's own API version (v2010, v1), which decides the twilio-go
    // import path and the RestClient field. Writing the CRD version over it
    // silently breaks both.
    public static final String CRD_VERSION = "crdVersion";
    public static final String PROVIDER_TITLE = "providerTitle";

    /**
     * Which resources get a Kind, keyed the way the superclass keys them.
     *
     * Computed from the whole document before any operation is grouped,
     * because of a hazard in the superclass: it calls
     * {@code clearTemplateFiles()} when a group yields no resource, and that
     * clears the generator's template lists -- global state. With one group per
     * resource, the first rejected group would silently empty every resource
     * after it. Rejecting the operations up front means no group is ever empty
     * and the clear never fires.
     */
    private final Set<String> wanted = new LinkedHashSet<>();

    /** The Kind each resource key belongs to, so grouping and naming agree. */
    private final Map<String, String> kinds = new LinkedHashMap<>();

    private String providerName = "twilio";
    private String groupName = "twilio.crossplane.io";
    private String crdVersion = "v1alpha1";

    /**
     * The output directory as given on the command line.
     *
     * {@code TwilioCodegenAdapter} redirects output to
     * {@code <out>/<domain>/<version>} -- right for a helper library laid out
     * per product, wrong for a provider repo whose paths are fixed
     * ({@code apis/}, {@code internal/}, {@code cmd/}). Captured before the
     * adapter runs and forced back afterwards.
     */
    private String repoRoot;

    public CrossplaneCodegen() {
        super();

        // Our templates, not the superclass's terraform ones.
        embeddedTemplateDir = templateDir = "crossplane-provider";

        cliOptions.add(new CliOption(GROUP_NAME, "The CRD API group (default: twilio.crossplane.io)"));
        cliOptions.add(new CliOption(CRD_VERSION, "The CRD API version (default: v1alpha1)"));
    }

    @Override
    public String getName() {
        return "twilio-crossplane";
    }

    @Override
    public String getHelp() {
        return "Generates a Crossplane provider from a Twilio OpenAPI description.";
    }

    @Override
    public void processOpts() {
        repoRoot = getOutputDir();

        super.processOpts();

        // Undo the <domain>/<version> redirect; see repoRoot.
        setOutputDir(repoRoot);

        if (additionalProperties.containsKey(GROUP_NAME)) {
            groupName = additionalProperties.get(GROUP_NAME).toString();
        }
        if (additionalProperties.containsKey(CRD_VERSION)) {
            crdVersion = additionalProperties.get(CRD_VERSION).toString();
        }
        additionalProperties.put(PROVIDER_NAME, providerName);
        additionalProperties.put(GROUP_NAME, groupName);
        additionalProperties.put(CRD_VERSION, crdVersion);
        additionalProperties.putIfAbsent(PROVIDER_TITLE, "Twilio");

        // One Kind per resource: a types.go and a controller each, which is
        // what the scaffold templates below expect to find.
        apiTemplateFiles.clear();
        apiTemplateFiles.put("types.mustache", ".go");
        apiTemplateFiles.put("controller.mustache", "-controller.go");
        // Each Kind is its own Go package, so each needs its own +groupName
        // marker and SchemeBuilder -- this is per Kind, not per repo. The
        // ProviderConfig package has its own in providerconfig_doc.mustache.
        apiTemplateFiles.put("groupversion.mustache", "-groupversion.go");

        apiTestTemplateFiles.clear();
        modelTestTemplateFiles.clear();
        apiDocTemplateFiles.clear();
        modelTemplateFiles.clear();
        modelDocTemplateFiles.clear();

        supportingFiles.clear();
        supportingFiles.add(new SupportingFile("gomod.mustache", "", "go.mod"));
        supportingFiles.add(new SupportingFile("Makefile.mustache", "", "Makefile"));
        supportingFiles.add(new SupportingFile("gitmodules.mustache", "", ".gitmodules"));
        supportingFiles.add(new SupportingFile("boilerplate.mustache", "hack", "boilerplate.go.txt"));
        supportingFiles.add(new SupportingFile("main.mustache", "cmd/provider", "main.go"));
        supportingFiles.add(new SupportingFile("version.mustache", "internal/version", "version.go"));
        supportingFiles.add(new SupportingFile("client.mustache",
                                               "internal/clients/" + providerName,
                                               providerName + ".go"));
        supportingFiles.add(new SupportingFile("config.mustache", "internal/controller/config", "config.go"));
        supportingFiles.add(new SupportingFile("controllers.mustache",
                                               "internal/controller",
                                               providerName + ".go"));
        supportingFiles.add(new SupportingFile("scheme.mustache", "apis", providerName + ".go"));
        supportingFiles.add(new SupportingFile("generate_go.mustache", "apis", "generate.go"));
        supportingFiles.add(new SupportingFile("providerconfig_types.mustache",
                                               "apis/" + crdVersion,
                                               "providerconfig_types.go"));
        supportingFiles.add(new SupportingFile("providerconfig_register.mustache",
                                               "apis/" + crdVersion,
                                               "register.go"));
        supportingFiles.add(new SupportingFile("providerconfig_doc.mustache", "apis/" + crdVersion, "doc.go"));
        supportingFiles.add(new SupportingFile("crossplane_yaml.mustache", "package", "crossplane.yaml"));
        supportingFiles.add(new SupportingFile("image_dockerfile.mustache",
                                               "cluster/images/provider-" + providerName,
                                               "Dockerfile"));
        supportingFiles.add(new SupportingFile("image_makefile.mustache",
                                               "cluster/images/provider-" + providerName,
                                               "Makefile"));
    }

    @Override
    public void processOpenAPI(final OpenAPI openAPI) {
        super.processOpenAPI(openAPI);

        // Again: the adapter re-redirects output while handling the document.
        setOutputDir(repoRoot);

        selectResources(openAPI);
    }

    /**
     * Which resources this document contributes a Kind for.
     *
     * The rule is the superclass's own -- a resource needs a create, a fetch
     * and a delete -- applied here rather than there so that a rejected
     * resource never reaches a group. Spelling it on the document also means
     * {@code hack/parity.py} and this agree on one rule rather than two.
     */
    private void selectResources(final OpenAPI openAPI) {
        wanted.clear();
        kinds.clear();

        final Map<String, Set<String>> verbs = new LinkedHashMap<>();
        final Map<String, String> names = new LinkedHashMap<>();

        if (openAPI.getPaths() == null) {
            return;
        }

        for (final Map.Entry<String, PathItem> entry : openAPI.getPaths().entrySet()) {
            final String path = entry.getKey();

            for (final Operation operation : entry.getValue().readOperations()) {
                final String operationId = operation.getOperationId();

                if (operationId == null || operationId.startsWith("List")) {
                    continue;
                }

                final String key = resourceKey(path);
                verbs.computeIfAbsent(key, k -> new HashSet<>()).add(verbOf(operationId));
                names.putIfAbsent(key, kindOf(path));
            }
        }

        verbs.forEach((key, found) -> {
            if (found.contains("CREATE") && found.contains("FETCH") && found.contains("DELETE")
                    && identifiable(openAPI, key)) {
                wanted.add(key);
                kinds.put(key, names.get(key));
            }
        });
    }

    /**
     * Whether the CREATE answers the identifier the fetch is addressed by.
     *
     * The superclass's fifth and least obvious rule. After grouping it takes
     * the fetch's last path parameter as {@code x-resource-id}, then drops the
     * resource unless that name is a property of what the <b>create</b>
     * answers -- because Create is where the external name is recorded, and a
     * create whose answer does not carry the id leaves nothing to record.
     *
     * This is why terraform-provider-twilio has no
     * {@code twilio_api_accounts_outgoing_caller_ids}: the resource is fetched
     * at {@code /OutgoingCallerIds/{Sid}} but created by
     * {@code CreateValidationRequest}, whose answer is a validation request
     * ({@code validation_code}, {@code phone_number}) with no {@code sid} in
     * it. See PARITY.md, where it was the one unexplained difference.
     *
     * Applied here for the same reason as the other four rules: a resource
     * rejected inside the superclass takes the whole run's template list with
     * it. Missing it cost 14 of api v2010's 23 Kinds and every scaffold file,
     * with the generator still exiting 0.
     */
    private boolean identifiable(final OpenAPI openAPI, final String key) {
        String id = null;
        Operation create = null;

        for (final Map.Entry<String, PathItem> entry : openAPI.getPaths().entrySet()) {
            if (!resourceKey(entry.getKey()).equals(key)) {
                continue;
            }

            final PathItem item = entry.getValue();

            for (final Operation operation : item.readOperations()) {
                final String operationId = operation.getOperationId();
                if (operationId == null) {
                    continue;
                }
                if (operationId.startsWith("Create")) {
                    create = operation;
                }
                if (operationId.startsWith("Fetch")) {
                    final List<String> pathParams = pathParams(item, operation);
                    if (!pathParams.isEmpty()) {
                        id = StringHelper.toSnakeCase(pathParams.get(pathParams.size() - 1));
                    }
                }
            }
        }

        if (create == null) {
            return false;
        }

        // Addressed by a query parameter rather than a path one; the
        // superclass keeps those and flags them has-fetch-with-query-params.
        if (id == null) {
            return true;
        }

        return properties(create).contains(id);
    }

    /** An operation's path parameters, including the ones on the path item. */
    private List<String> pathParams(final PathItem item, final Operation operation) {
        final List<String> names = new ArrayList<>();

        for (final List<Parameter> params : List.of(
                item.getParameters() == null ? List.<Parameter>of() : item.getParameters(),
                operation.getParameters() == null ? List.<Parameter>of() : operation.getParameters())) {
            for (final Parameter param : params) {
                if ("path".equals(param.getIn())) {
                    names.add(param.getName());
                }
            }
        }

        return names;
    }

    /** The property names of what an operation answers, following allOf. */
    private Set<String> properties(final Operation operation) {
        final Set<String> names = new LinkedHashSet<>();

        if (operation.getResponses() == null) {
            return names;
        }

        operation.getResponses().forEach((code, response) -> {
            if (!code.startsWith("2") || response.getContent() == null) {
                return;
            }

            response.getContent().forEach((type, media) -> {
                final Schema<?> schema = ModelUtils.getReferencedSchema(this.openAPI, media.getSchema());
                if (schema == null) {
                    return;
                }
                if (schema.getProperties() != null) {
                    names.addAll(schema.getProperties().keySet());
                }
                if (schema.getAllOf() != null) {
                    for (final Object member : schema.getAllOf()) {
                        final Schema<?> resolved =
                            ModelUtils.getReferencedSchema(this.openAPI, (Schema<?>) member);
                        if (resolved != null && resolved.getProperties() != null) {
                            names.addAll(resolved.getProperties().keySet());
                        }
                    }
                }
            });
        });

        return names;
    }

    /** {@code PathUtils}, in the order the superclass composes them. */
    private String resourceKey(final String path) {
        return PathUtils.removePathParamIds(PathUtils.removeTrailingPathParam(PathUtils.removeExtension(path)));
    }

    /** {@code Utility.populateCrudOperations}: the verb is the id's prefix. */
    private String verbOf(final String operationId) {
        for (final String verb : new String[] { "CREATE", "FETCH", "UPDATE", "PATCH", "DELETE" }) {
            if (operationId.toLowerCase(Locale.ROOT).startsWith(verb.toLowerCase(Locale.ROOT))) {
                return verb;
            }
        }
        return "READ";
    }

    /**
     * The Kind, which is the resource name the superclass would give it.
     *
     * {@code /2010-04-01/Accounts/{AccountSid}/Addresses/{Sid}.json} becomes
     * {@code AccountsAddresses} -- the path's own segments, minus the version
     * prefix and the parameters. Long, and unique: these are
     * terraform-provider-twilio's 164 resource names, which collide only when
     * the segments are dropped.
     */
    private String kindOf(final String path) {
        return PathUtils.cleanPathAndRemoveFirstElement(path).replace("/", "");
    }

    /**
     * One group per resource, so each Kind gets its own files.
     *
     * The superclass clears the document's tags to put every operation in one
     * file, which is right for a Terraform provider (one {@code api_default.go}
     * per product) and wrong for a Crossplane one, where a Kind is a package.
     * The group key is the Kind.
     *
     * An operation belonging to a resource that is not getting a Kind is
     * dropped rather than grouped; see {@link #wanted}.
     */
    @Override
    public void addOperationToGroup(final String tag,
                                    final String resourcePath,
                                    final Operation operation,
                                    final CodegenOperation co,
                                    final Map<String, List<CodegenOperation>> operations) {
        final String key = resourceKey(co.path);

        if (!wanted.contains(key)) {
            return;
        }

        super.addOperationToGroup(kinds.get(key), resourcePath, operation, co, operations);
    }

    /**
     * The Crossplane shape, on top of the resource the superclass assembled.
     *
     * {@code spec.forProvider} is what a person may write and
     * {@code status.atProvider} is what the API answers, and for Twilio those
     * are spelled differently: a request property is {@code FriendlyName} and
     * the response property is {@code friendly_name}. The superclass already
     * puts {@code x-name-in-snake-case} on every parameter, so the two are
     * matched here rather than by name.
     */
    @Override
    @SuppressWarnings("unchecked")
    public OperationsMap postProcessOperationsWithModels(final OperationsMap objs, final List<ModelMap> allModels) {
        // The superclass calls clearTemplateFiles() when it rejects the last
        // resource in a group, and that empties the generator's template lists
        // for the WHOLE run -- every later Kind and every supporting file. The
        // rejections are pre-empted in selectResources, so this should never
        // fire; restored anyway, because the failure mode is a generator that
        // exits 0 having written part of a provider.
        final Map<String, String> apiTemplates = new LinkedHashMap<>(apiTemplateFiles);
        final List<SupportingFile> scaffold = new ArrayList<>(supportingFiles);

        final OperationsMap results = super.postProcessOperationsWithModels(objs, allModels);

        if (apiTemplateFiles.isEmpty() && !apiTemplates.isEmpty()) {
            apiTemplateFiles.putAll(apiTemplates);
            supportingFiles.clear();
            supportingFiles.addAll(scaffold);

            throw new IllegalStateException(
                "the generator rejected every resource in a group; selectResources and "
                + "TwilioTerraformGenerator disagree about which resources exist, and continuing "
                + "would write a partial provider");
        }

        final Object resources = results.get("resources");
        if (!(resources instanceof Iterable)) {
            return results;
        }

        for (final Object entry : (Iterable<Object>) resources) {
            final Map<String, Object> resource = (Map<String, Object>) entry;
            final String kind = resource.get("name").toString();

            resource.put("kind", kind);
            resource.put("kindPackage", StringHelper.toSnakeCase(kind));
            resource.put("kindLower", kind.toLowerCase(Locale.ROOT));
            resource.put("groupName", groupName);
            resource.put("crdVersion", crdVersion);
            final List<Map<String, Object>> observation = observation(resource);
            final List<Map<String, Object>> spec = specFields(resource);

            pair(spec, observation);

            resource.put("observation", observation);
            resource.put("specFields", spec);

            resource.put("createFields", operationFields(resource, "CREATE"));
            resource.put("updateFields", operationFields(resource, "UPDATE"));

            // x-resource-id is put on the CREATE operation only, so the read,
            // the update and the delete have to be told what their own
            // identifier is called. Without this every one of them treats the
            // id as an ordinary parent parameter and looks for it in
            // spec.forProvider, where it is not, because it is the external
            // name.
            final Object create = resource.get("CREATE");
            final Object id = create instanceof CodegenOperation
                    ? ((CodegenOperation) create).vendorExtensions.get("x-resource-id")
                    : null;

            for (final String verb : new String[] { "CREATE", "FETCH", "UPDATE", "DELETE" }) {
                final Object operation = resource.get(verb);
                if (operation instanceof CodegenOperation) {
                    callArgs((CodegenOperation) operation, id == null ? null : String.valueOf(id));
                }
            }
        }

        // The scaffold templates iterate apiInfo.apis[].operations to name every
        // Kind's package, so the group's own operations map carries them too.
        final Object first = ((Iterable<Object>) resources).iterator().hasNext()
                ? ((Iterable<Object>) resources).iterator().next()
                : null;
        if (first != null) {
            final Map<String, Object> resource = (Map<String, Object>) first;
            results.getOperations().put("kind", resource.get("kind"));
            results.getOperations().put("kindPackage", resource.get("kindPackage"));
        }

        return results;
    }

    /**
     * The fields one operation sends, as Go names and call expressions.
     *
     * Per operation rather than from the merged {@code specFields}, because
     * the create and the update do NOT take the same parameters -- which is
     * what the superclass's {@code x-update-after-create} flags. A field only
     * the update accepts ({@code Status} on a call, {@code Hold} on a
     * participant, {@code AccountSid} on a phone number being moved between
     * accounts) has no setter on the create's params struct, so building the
     * create from the merged list does not compile.
     *
     * Path parameters are left out: twilio-go takes those positionally.
     */
    private List<Map<String, Object>> operationFields(final Map<String, Object> resource, final String verb) {
        final List<Map<String, Object>> fields = new ArrayList<>();
        final Object operation = resource.get(verb);

        if (!(operation instanceof CodegenOperation)) {
            return fields;
        }

        for (final CodegenParameter param : ((CodegenOperation) operation).allParams) {
            if (param.isPathParam) {
                continue;
            }

            final String goName = StringHelper.camelize(param.paramName);
            final Map<String, Object> field = new LinkedHashMap<>();
            field.put("goName", goName);
            field.put("required", param.required);
            field.put("setExpr", setExpr("cr.Spec.ForProvider." + goName, param.dataType));
            fields.add(field);
        }

        return fields;
    }

    /**
     * The expression handed to a twilio-go setter.
     *
     * Normally the spec field itself, because {@link #specType} gives it the
     * type the SDK wants. The exception is a date-time: the SDK takes a
     * {@code time.Time} and a CRD field cannot be one, so it is carried as an
     * RFC 3339 string and parsed at the call.
     */
    private String setExpr(final String field, final String dataType) {
        if ("time.Time".equals(dataType)) {
            return "twilio.Time(" + field + ")";
        }

        return field;
    }

    /**
     * The Go expression for each positional argument of a twilio-go call.
     *
     * twilio-go takes an operation's path parameters positionally and the rest
     * in a params struct: {@code FetchAddress(Sid, &params)},
     * {@code FetchRecording(CallSid, Sid, &params)}. The last one is the
     * resource's own id, which Crossplane keeps in the external-name
     * annotation; the ones before it identify the parent and are fields a
     * person sets.
     *
     * Decided here because a template cannot compare a parameter's name to
     * {@code x-resource-id} to tell which is which.
     */
    private void callArgs(final CodegenOperation operation, final String externalName) {
        final List<Map<String, Object>> args = new ArrayList<>();

        for (final CodegenParameter param : operation.pathParams) {
            final Map<String, Object> arg = new LinkedHashMap<>();
            if (param.paramName.equals(externalName)) {
                arg.put("expr", "id");
            } else {
                arg.put("expr", "cr.Spec.ForProvider." + StringHelper.camelize(param.paramName));
            }
            args.add(arg);
        }

        operation.vendorExtensions.put("x-cp-args", args);
        operation.vendorExtensions.put("x-cp-has-args", !args.isEmpty());
    }

    /**
     * Matches a spec field to the status field that echoes it.
     *
     * Twilio takes {@code FriendlyName} and answers {@code friendly_name}, so
     * nothing pairs by name and a comparison written by name would compare
     * nothing at all -- {@code upToDate} would return true for every resource
     * and the provider would never detect drift, while compiling and
     * reconciling perfectly. Paired through the same
     * {@code StringHelper.toSnakeCase} that produced the response names.
     *
     * A field with no match is one the API does not echo back --
     * {@code StatusCallback}, {@code SendDigits}, {@code SipAuthPassword}; 132
     * of api v2010's 225 create-body properties are write-only. Those cannot
     * be diffed without reporting drift on every reconcile, so they are left
     * out of the comparison rather than guessed at.
     */
    private void pair(final List<Map<String, Object>> spec, final List<Map<String, Object>> observation) {
        for (final Map<String, Object> field : spec) {
            final String wire = StringHelper.toSnakeCase(String.valueOf(field.get("paramName")));

            observation
                .stream()
                .filter(observed -> wire.equals(observed.get("jsonName")))
                .filter(observed -> observed.get("goType").equals(field.get("goType")))
                .findFirst()
                .ifPresent(observed -> {
                    field.put("observedGoName", observed.get("goName"));
                    // Only a scalar is compared: a string holding JSON is
                    // compared by value elsewhere, and a list would need an
                    // order-insensitive comparison the API does not promise.
                    field.put("comparable", !String.valueOf(field.get("goType")).startsWith("[]"));
                });
        }
    }

    /**
     * {@code spec.forProvider}: what a person may write.
     *
     * The superclass has already merged the create's parameters with the ones
     * only the update accepts, and marked each with the Terraform requirement
     * it implies. Reused as-is, minus one thing: the external name. The merged
     * list contains the fetch's path parameters, so the resource's own id is in
     * there, and a {@code sid} field in {@code spec.forProvider} would be a
     * second, contradictory place to say what
     * {@code crossplane.io/external-name} already says.
     *
     * The Go type is settled here rather than in the template because
     * {@code dataType} is what twilio-go would use, and two of those are not
     * things a CRD can hold: {@code interface{}} has no schema, and
     * controller-gen rejects {@code float32}.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> specFields(final Map<String, Object> resource) {
        final List<Map<String, Object>> fields = new ArrayList<>();
        final Object schema = resource.get("schema");
        final Object create = resource.get("CREATE");

        final String externalName = create instanceof CodegenOperation
                ? String.valueOf(((CodegenOperation) create).vendorExtensions.get("x-resource-id"))
                : null;

        if (!(schema instanceof Iterable)) {
            return fields;
        }

        final Set<String> seen = new HashSet<>();

        for (final Object entry : (Iterable<Object>) schema) {
            if (!(entry instanceof CodegenParameter)) {
                continue;
            }

            final CodegenParameter param = (CodegenParameter) entry;

            if (param.paramName.equals(externalName) || !seen.add(param.paramName)) {
                continue;
            }

            final Map<String, Object> field = new LinkedHashMap<>();
            field.put("goName", StringHelper.camelize(param.paramName));
            // The wire name, which for a request is Twilio's PascalCase --
            // spec.forProvider spells fields the way the API takes them.
            field.put("jsonName", param.baseName);
            field.put("goType", specType(param.dataType));
            field.put("required", param.required);
            field.put("description", oneLine(param.description));
            field.put("paramName", param.paramName);
            field.put("isPathParam", param.isPathParam);
            fields.add(field);
        }

        return fields;
    }

    /**
     * A description that fits on one Go comment line.
     *
     * Twilio's descriptions are markdown paragraphs with links in them, and a
     * newline inside a {@code //} comment is a syntax error in the next line
     * of the struct.
     */
    private String oneLine(final String description) {
        return description == null ? "" : description.replaceAll("\\s+", " ").trim();
    }

    /** The Go type a spec field holds; see {@link #specFields}. */
    private String specType(final String dataType) {
        if (dataType == null) {
            return "string";
        }
        if (dataType.startsWith("[]")) {
            final String element = specType(dataType.substring(2));
            // A list of something a CRD cannot describe travels as one JSON
            // string rather than as a list of strings each holding JSON.
            return "string".equals(element) ? "[]string" : "string";
        }

        switch (dataType) {
            // Passed through EXACTLY as twilio-go spells it, because the
            // generated controller hands this field straight to a twilio-go
            // setter: SetMaxPrice takes a float32 and SetValidityPeriod an
            // int, so widening them here would not compile. controller-gen
            // needs crd:allowDangerousTypes for the floats, which is why
            // apis/generate.go passes it.
            case "bool":
            case "int":
            case "int32":
            case "int64":
            case "float32":
            case "float64":
            case "string":
                return dataType;
            default:
                // interface{}, map[string]interface{}, a named model: JSON in
                // a string, which is what both existing providers do.
                return "string";
        }
    }

    /**
     * {@code status.atProvider}: the properties of what the fetch answers.
     *
     * The superclass generates no models at all --
     * {@code postProcessAllModels} returns an empty map, because a Terraform
     * provider reads its types from twilio-go. This provider does too, so the
     * observation is a projection of the twilio-go struct rather than a type of
     * its own, and what is needed here is the list of its fields.
     */
    private List<Map<String, Object>> observation(final Map<String, Object> resource) {
        final List<Map<String, Object>> fields = new ArrayList<>();
        final Object fetch = resource.get("FETCH");

        if (!(fetch instanceof CodegenOperation)) {
            return fields;
        }

        final Optional<Schema> schema = ((CodegenOperation) fetch).responses
            .stream()
            .filter(response -> response.is2xx)
            .map(response -> response.schema)
            .filter(Schema.class::isInstance)
            .map(Schema.class::cast)
            .findFirst();

        if (schema.isEmpty()) {
            return fields;
        }

        final Schema<?> resolved = ModelUtils.getReferencedSchema(this.openAPI, schema.get());
        if (resolved == null || resolved.getProperties() == null) {
            return fields;
        }

        resolved.getProperties().forEach((name, property) -> {
            final Map<String, Object> field = new LinkedHashMap<>();
            // The Go field on the twilio-go struct, which is the camelised
            // response property; the CRD spells it as the API does.
            field.put("goName", StringHelper.camelize(name));
            field.put("jsonName", name);
            field.put("goType", observationType((Schema<?>) property));
            fields.add(field);
        });

        return fields;
    }

    /**
     * The Go type a status field holds.
     *
     * Everything that is not a plain scalar becomes a string holding JSON, the
     * way both existing providers do it: a CRD cannot hold an arbitrary
     * document, and Twilio answers plenty of them -- {@code subresource_uris},
     * {@code links}, the {@code uri-map} format. A scalar keeps its type so
     * that a person comparing {@code status.atProvider} to what they asked for
     * is comparing like with like.
     */
    private String observationType(final Schema<?> property) {
        final Schema<?> resolved = ModelUtils.getReferencedSchema(this.openAPI, property);
        final String type = resolved == null ? null : resolved.getType();

        if ("boolean".equals(type)) {
            return "bool";
        }
        if ("integer".equals(type)) {
            return "int64";
        }
        if ("number".equals(type)) {
            return "float64";
        }

        // Including "string": Twilio's date-time-rfc-2822, phone-number,
        // iso-country-code and friends are strings on the wire and are left as
        // strings here. RFC 2822 is not RFC 3339, so a time.Time would fail to
        // unmarshal every timestamp the API sends.
        return "string";
    }

    /** Where each Kind's generated files land. */
    @Override
    public String apiFilename(final String templateName, final String tag) {
        final String pkg = StringHelper.toSnakeCase(tag);

        if (templateName.startsWith("groupversion")) {
            return repoRoot + File.separator + "apis" + File.separator + pkg + File.separator + crdVersion
                    + File.separator + "groupversion_info.go";
        }

        if (templateName.startsWith("types")) {
            return repoRoot + File.separator + "apis" + File.separator + pkg + File.separator + crdVersion
                    + File.separator + pkg + "_types.go";
        }

        return repoRoot + File.separator + "internal" + File.separator + "controller" + File.separator + pkg
                + File.separator + pkg + ".go";
    }
}
