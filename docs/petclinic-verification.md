# Coverage verification against Spring PetClinic

Full cross-check of the endpoint index against [spring-projects/spring-petclinic](https://github.com/spring-projects/spring-petclinic) (shallow clone, main branch, July 2026). Ground truth was built by hand from every mapping annotation in the source; each endpoint was then resolved through the real stdio JSON-RPC interface (`initialize` → `tools/call resolveEndpoint`) and compared on FQCN, method name, and line number.

## Result

| Metric | Count |
|---|---|
| Total endpoints (ground truth) | 17 |
| Resolved correctly (class#method + line) | **17** |
| Mismatched | 0 |
| Not indexed | 0 |

## Cases covered by the ground truth

- Class-level `@RequestMapping("/owners/{ownerId}")` prefix combined with method-level mappings (`PetController`)
- Path variables in class-level prefix and multi-variable patterns (`/owners/{ownerId}/pets/{petId}/visits/new`)
- Package-private controller classes (all PetClinic controllers)
- Array-form path attribute (`@GetMapping({ "/vets" })`)
- Root path mapping (`@GetMapping("/")`)
- Literal vs variable overlap: `GET /owners/new` and `GET /owners/find` each match both a literal pattern and `/owners/{ownerId}` — resolved to the literal one, matching Spring's specificity rules

## Full resolution table

| Request | Resolved handler | Line |
|---|---|---|
| GET /owners/1/pets/2/visits/new | owner.VisitController#initNewVisitForm | 90 |
| POST /owners/1/pets/2/visits/new | owner.VisitController#processNewVisitForm | 97 |
| GET /owners/1/pets/new | owner.PetController#initCreationForm | 99 |
| POST /owners/1/pets/new | owner.PetController#processCreationForm | 106 |
| GET /owners/1/pets/9/edit | owner.PetController#initUpdateForm | 129 |
| POST /owners/1/pets/9/edit | owner.PetController#processUpdateForm | 134 |
| GET /owners/new | owner.OwnerController#initCreationForm | 72 |
| POST /owners/new | owner.OwnerController#processCreationForm | 77 |
| GET /owners/find | owner.OwnerController#initFindForm | 89 |
| GET /owners | owner.OwnerController#processFindForm | 94 |
| GET /owners/1/edit | owner.OwnerController#initUpdateOwnerForm | 136 |
| POST /owners/1/edit | owner.OwnerController#processUpdateOwnerForm | 141 |
| GET /owners/1 | owner.OwnerController#showOwner | 166 |
| GET /vets.html | vet.VetController#showVetList | 44 |
| GET /vets | vet.VetController#showResourcesVetList | 65 |
| GET / | system.WelcomeController#welcome | 25 |
| GET /oups | system.CrashController#triggerException | 31 |

(Package prefix `org.springframework.samples.petclinic.` omitted for brevity. Reported line numbers point at the start of the handler's declaration.)

## Bug found and fixed during verification

Building the ground truth surfaced a latent resolution bug: `resolve()` returned the *first* matching pattern in scan order, while Spring always picks the *most specific* one. `GET /owners/new` matches both `/owners/new` and `/owners/{ownerId}`, and only resolved correctly by declaration-order luck. Fixed by preferring the match with the fewest variable segments; covered by a regression test where the variable pattern is deliberately declared before the literal one.

## Known limitations (not hit by PetClinic)

- Constant-referenced paths (`@GetMapping(SOME_CONSTANT)`) are not indexed
- Ant-style wildcards (`*`, `**`) are not supported in patterns
- One harness-side note: the MCP Java SDK's stdio transport drops responses if a client floods many requests without reading responses ("Failed to enqueue message"); pacing requests client-side (as real MCP clients do) avoids it
