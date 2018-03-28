package edu.gatech.chai.gtfhir2.mapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.hl7.fhir.dstu3.model.CodeableConcept;
import org.hl7.fhir.dstu3.model.Coding;
import org.hl7.fhir.dstu3.model.DateTimeType;
import org.hl7.fhir.dstu3.model.Encounter;
import org.hl7.fhir.dstu3.model.IdType;
import org.hl7.fhir.dstu3.model.Procedure;
import org.hl7.fhir.dstu3.model.Procedure.ProcedurePerformerComponent;
import org.hl7.fhir.dstu3.model.Reference;
import org.hl7.fhir.dstu3.model.Type;
import org.hl7.fhir.exceptions.FHIRException;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.WebApplicationContext;

import ca.uhn.fhir.rest.param.TokenParam;
import edu.gatech.chai.gtfhir2.provider.EncounterResourceProvider;
import edu.gatech.chai.gtfhir2.provider.PatientResourceProvider;
import edu.gatech.chai.gtfhir2.provider.PractitionerResourceProvider;
import edu.gatech.chai.gtfhir2.provider.ProcedureResourceProvider;
import edu.gatech.chai.gtfhir2.utilities.CodeableConceptUtil;
import edu.gatech.chai.omopv5.jpa.entity.Concept;
import edu.gatech.chai.omopv5.jpa.entity.FPerson;
import edu.gatech.chai.omopv5.jpa.entity.ProcedureOccurrence;
import edu.gatech.chai.omopv5.jpa.entity.Provider;
import edu.gatech.chai.omopv5.jpa.entity.VisitOccurrence;
import edu.gatech.chai.omopv5.jpa.service.ConceptService;
import edu.gatech.chai.omopv5.jpa.service.FPersonService;
import edu.gatech.chai.omopv5.jpa.service.ParameterWrapper;
import edu.gatech.chai.omopv5.jpa.service.ProcedureOccurrenceService;
import edu.gatech.chai.omopv5.jpa.service.ProviderService;
import edu.gatech.chai.omopv5.jpa.service.VisitOccurrenceService;

public class OmopProcedure extends BaseOmopResource<Procedure, ProcedureOccurrence, ProcedureOccurrenceService>
		implements IResourceMapping<Procedure, ProcedureOccurrence> {

	private static OmopProcedure omopProcedure = new OmopProcedure();
	private ConceptService conceptService;
	private FPersonService fPersonService;
	private VisitOccurrenceService visitOccurrenceService;
	private ProviderService providerService;

	private static long OMOP_PROCEDURE_TYPE_DEFAULT = 44786630L;
	
	public OmopProcedure(WebApplicationContext context) {
		super(context, ProcedureOccurrence.class, ProcedureOccurrenceService.class, ProcedureResourceProvider.getType());
		initialize(context);
	}

	public OmopProcedure() {
		super(ContextLoaderListener.getCurrentWebApplicationContext(), ProcedureOccurrence.class, ProcedureOccurrenceService.class, ProcedureResourceProvider.getType());
		initialize(ContextLoaderListener.getCurrentWebApplicationContext());
	}
	
	private void initialize(WebApplicationContext context) {
		conceptService = context.getBean(ConceptService.class);
		fPersonService = context.getBean(FPersonService.class);
		visitOccurrenceService = context.getBean(VisitOccurrenceService.class);
		providerService = context.getBean(ProviderService.class);
	}
	
	public static OmopProcedure getInstance() {
		return omopProcedure;
	}
	
	@Override
	public Long toDbase(Procedure fhirResource, IdType fhirId) throws FHIRException {
		Long omopId = null;
		ProcedureOccurrence procedureOccurrence = null;
		if (fhirId == null) {
			// Create
			procedureOccurrence = new ProcedureOccurrence();
		} else {
			// Update
			omopId = IdMapping.getOMOPfromFHIR(fhirId.getIdPartAsLong(), ProcedureResourceProvider.getType());
			procedureOccurrence = getMyOmopService().findById(omopId);
			
			if (procedureOccurrence == null) {
				return null;
			}
		}

		// Procedure type concept mapping.
//		CodeableConcept categoryCodeableConcept = fhirResource.getCategory();
//		Concept procedureTypeConcept = null;
//		if (!categoryCodeableConcept.isEmpty()) {
//			List<Coding> codings = categoryCodeableConcept.getCoding();
//			for (Coding coding: codings) {
//				procedureTypeConcept = CodeableConceptUtil.getOmopConceptWithFhirConcept(conceptService, coding);
//				if (procedureTypeConcept != null) break;
//			}
//		}		
//		
//		if (procedureTypeConcept != null) {
//			procedureOccurrence.setProcedureTypeConcept(procedureTypeConcept);
//		}
		// Procedure type concept is not mappable. But, this is required.
		// Hardcode to 44786630L (Primary Procedure)
		procedureOccurrence.setProcedureTypeConcept(new Concept(OMOP_PROCEDURE_TYPE_DEFAULT));
		
		// Procedure concept mapping
		CodeableConcept codeCodeableConcept = fhirResource.getCode();
		Concept procedureConcept = null;
		if (!codeCodeableConcept.isEmpty()) {
			List<Coding> codings = codeCodeableConcept.getCoding();
			for (Coding coding: codings) {
				procedureConcept = CodeableConceptUtil.getOmopConceptWithFhirConcept(conceptService, coding);
				if (procedureConcept != null) break;
			}
		}
		
		if (procedureConcept != null) {
			procedureOccurrence.setProcedureConcept(procedureConcept);
		}
		
		// Person mapping
		Reference patientReference = fhirResource.getSubject();
		if (patientReference.getReferenceElement().getResourceType().equals(PatientResourceProvider.getType())) {
			Long patientFhirId = patientReference.getReferenceElement().getIdPartAsLong();
			Long omopFPersonId = IdMapping.getOMOPfromFHIR(patientFhirId, PatientResourceProvider.getType());
			if (omopFPersonId == null) {
				throw new FHIRException("Unable to get OMOP person ID from FHIR patient ID");
			} 
			
			FPerson fPerson = fPersonService.findById(omopFPersonId);
			if (fPerson != null) {
				procedureOccurrence.setFPerson(fPerson);
			} else {
				throw new FHIRException("Unable to find the person from OMOP database");
			}
		} else {
			throw new FHIRException("Subject must be Patient");
		}

		// Visit Occurrence mapping
		Reference encounterReference = fhirResource.getContext();
		if (encounterReference.getReferenceElement().getResourceType().equals(EncounterResourceProvider.getType())) {
			Long encounterFhirId = encounterReference.getReferenceElement().getIdPartAsLong();
			Long omopVisitOccurrenceId = IdMapping.getOMOPfromFHIR(encounterFhirId, EncounterResourceProvider.getType());
			if (omopVisitOccurrenceId == null) {
				throw new FHIRException("Unable to get OMOP Visit Occurrence ID from FHIR encounter ID");
			}
			
			VisitOccurrence visitOccurrence = visitOccurrenceService.findById(omopVisitOccurrenceId);
			if (visitOccurrence != null) {
				procedureOccurrence.setVisitOccurrence(visitOccurrence);
			} else {
				throw new FHIRException("Unable to find the visit occurrence from OMOP database");
			}
		} else {
			throw new FHIRException("Context must be Encounter");
		}

		// Provider mapping
		List<ProcedurePerformerComponent> performers = fhirResource.getPerformer();
		for (ProcedurePerformerComponent performer: performers) {
			if (performer.getActor().getReferenceElement().getResourceType().equals(PractitionerResourceProvider.getType())) {
				Long performerFhirId = performer.getActor().getReferenceElement().getIdPartAsLong();
				Long omopProviderId = IdMapping.getOMOPfromFHIR(performerFhirId, PractitionerResourceProvider.getType());
				if (omopProviderId == null) continue;
				Provider provider = providerService.findById(omopProviderId);
				if (provider == null || provider.getId() == 0L) continue;
				
				// specialty mapping
				CodeableConcept roleCodeableConcept = performer.getRole();
				Concept specialtyConcept = null;
				if (!roleCodeableConcept.isEmpty()) {
					List<Coding> codings = roleCodeableConcept.getCoding();
					for (Coding coding: codings) {
						if (!coding.isEmpty()) {
							specialtyConcept = CodeableConceptUtil.getOmopConceptWithFhirConcept(conceptService, coding);
							if (specialtyConcept != null) {
								if (provider.getSpecialtyConcept() == null || provider.getSpecialtyConcept().getId() == 0L) {
									// We have specialty information but provider table does not have this.
									// We can populate.
									provider.setSpecialtyConcept(specialtyConcept);
									providerService.update(provider);
									break;
								}
							}
						}
					}
				}
				
				procedureOccurrence.setProvider(provider);
				break;
			}
		}
		
		// Procedure Date mapping. Use start date for Period.
		Type performedType = fhirResource.getPerformed();
		if (!performedType.isEmpty()) {
			Date performedDate = null;
			if (performedType instanceof DateTimeType) {
				// PerformedDateTime
				performedDate = performedType.castToDateTime(performedType).getValue();
			} else {
				// PerformedPeriod
				performedDate = performedType.castToPeriod(performedType).getStart();
			}
			
			if (performedDate != null)
				procedureOccurrence.setProcedureDate(performedDate);
		}
		
		Long OmopRecordId = null;
		if (omopId == null) {
			OmopRecordId = getMyOmopService().create(procedureOccurrence).getId();
		} else {
			OmopRecordId = getMyOmopService().update(procedureOccurrence).getId();
		}
		
		return IdMapping.getFHIRfromOMOP(OmopRecordId, ProcedureResourceProvider.getType());
	}

//	@Override
//	public Procedure constructResource(Long fhirId, ProcedureOccurrence entity,List<String> includes) {
//		Procedure procedure = constructFHIR(fhirId,entity); //Assuming default active state
//		return procedure;
//	}

	@Override
	public Procedure constructFHIR(Long fhirId, ProcedureOccurrence entity) {
		Procedure procedure = new Procedure(); //Assuming default active state
		procedure.setId(new IdType(fhirId));

		// Set subject 
		Reference patientReference = new Reference(new IdType(PatientResourceProvider.getType(), entity.getFPerson().getId()));
		patientReference.setDisplay(entity.getFPerson().getNameAsSingleString());
		procedure.setSubject(patientReference);
		
		// TODO: We put completed as a default. Revisit this
		procedure.setStatus(Procedure.ProcedureStatus.COMPLETED);
		
		// Procedure code concept mapping
		Concept procedureConcept = entity.getProcedureConcept();
		CodeableConcept procedureCodeableConcept = null;
		try {
			procedureCodeableConcept = CodeableConceptUtil.getCodeableConceptFromOmopConcept(procedureConcept);
		} catch (FHIRException e) {
			e.printStackTrace();
		}

		if (procedureCodeableConcept != null) {
			procedure.setCode(procedureCodeableConcept);
		}

		// Procedure category mapping 
//		Concept procedureTypeConcept = entity.getProcedureTypeConcept();
//		CodeableConcept procedureTypeCodeableConcept = null;
//		try {
//			procedureTypeCodeableConcept = CodeableConceptUtil.getCodeableConceptFromOmopConcept(procedureTypeConcept);
//		} catch (FHIRException e) {
//			e.printStackTrace();
//		}
//
//		if (procedureTypeCodeableConcept != null) {
//			procedure.setCategory(procedureTypeCodeableConcept);
//		}
		
		// Context mapping
		VisitOccurrence visitOccurrence = entity.getVisitOccurrence();
		if (visitOccurrence != null) {
			Reference contextReference = new Reference(new IdType(EncounterResourceProvider.getType(), visitOccurrence.getId())); 
			procedure.setContext(contextReference);
		}
		
		// Performer mapping
		Provider provider = entity.getProvider();
		if (provider != null && provider.getId() != 0L) {
			ProcedurePerformerComponent performer = new ProcedurePerformerComponent();
			
			// actor mapping
			Long providerFhirId = IdMapping.getFHIRfromOMOP(provider.getId(), PractitionerResourceProvider.getType());
			Reference actorReference = new Reference(new IdType(PractitionerResourceProvider.getType(), providerFhirId));
			performer.setActor(actorReference);
			
			// role mapping
			Concept providerSpecialtyConcept = provider.getSpecialtyConcept();
			if (providerSpecialtyConcept != null && providerSpecialtyConcept.getId() != 0L) {
				CodeableConcept performerRoleCodeableConcept = null;
				try {
					performerRoleCodeableConcept = CodeableConceptUtil.getCodeableConceptFromOmopConcept(providerSpecialtyConcept);
				} catch (FHIRException e) {
					e.printStackTrace();
				}
	
				if (performerRoleCodeableConcept != null) {
					performer.setRole(performerRoleCodeableConcept);
				}
			}
			procedure.addPerformer(performer);
		}
		
		// Location mapping
		// TODO: Add location after Location mapping is done.
		
		// Performed DateTime mapping
		DateTimeType date = new DateTimeType(entity.getProcedureDate());
		procedure.setPerformed(date);
		
		return procedure;
	}

	@Override
	public List<ParameterWrapper> mapParameter(String parameter, Object value) {
		List<ParameterWrapper> mapList = new ArrayList<ParameterWrapper>();
		ParameterWrapper paramWrapper = new ParameterWrapper();
		switch (parameter) {
		case Procedure.SP_RES_ID:
			String procedureId = ((TokenParam) value).getValue();
			paramWrapper.setParameterType("Long");
			paramWrapper.setParameters(Arrays.asList("id"));
			paramWrapper.setOperators(Arrays.asList("="));
			paramWrapper.setValues(Arrays.asList(procedureId));
			paramWrapper.setRelationship("or");
			mapList.add(paramWrapper);
			break;
		case Procedure.SP_CODE:
			String system = ((TokenParam) value).getSystem();
			String code = ((TokenParam) value).getValue();
			
			if ((system == null || system.isEmpty()) && (code == null || code.isEmpty()))
				break;
			
			String omopVocabulary = "None";
			if (system != null && !system.isEmpty()) {
				try {
					omopVocabulary = OmopCodeableConceptMapping.omopVocabularyforFhirUri(system);
				} catch (FHIRException e) {
					e.printStackTrace();
				}
			} 

			paramWrapper.setParameterType("String");
			if ("None".equals(omopVocabulary) && code != null && !code.isEmpty()) {
				paramWrapper.setParameters(Arrays.asList("procedureConcept.conceptCode"));
				paramWrapper.setOperators(Arrays.asList("="));
				paramWrapper.setValues(Arrays.asList(code));
			} else if (!"None".equals(omopVocabulary) && (code == null || code.isEmpty())) {
				paramWrapper.setParameters(Arrays.asList("procedureConcept.vocabulary.id"));
				paramWrapper.setOperators(Arrays.asList("="));
				paramWrapper.setValues(Arrays.asList(omopVocabulary));				
			} else {
				paramWrapper.setParameters(Arrays.asList("procedureConcept.vocabulary.id", "procedureConcept.conceptCode"));
				paramWrapper.setOperators(Arrays.asList("=","="));
				paramWrapper.setValues(Arrays.asList(omopVocabulary, code));
			}
			paramWrapper.setRelationship("and");
			mapList.add(paramWrapper);
			break;
		default:
			mapList = null;
		}
		
		return mapList;
	}

}
