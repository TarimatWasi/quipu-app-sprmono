# Lineamientos del repositorio

- **Documentación:** se consulta y se mantiene en Confluence (sitio `tarimat-wasi`). No se crea documentación en este repositorio (ni `.md` ni `docs/`). Referenciar páginas por título, nunca por ruta de archivo.
- **Antes de escribir código:** leer "Perfil técnico de Quipu" (espacio Quipu) y las guías del espacio Yachay Wasi que ese perfil adopta ("Guía Spring", "Guía GitHub Actions y calidad de CI", "Política de componentes nuevos").
- **Actividades:** toda tarea vive en Jira (proyecto `TAR`). Ramas, commits y PRs citan la clave (`TAR-12`).
- **Puerta de calidad:** `mvn -B verify`.
- **Revisión y alineación:** ejecutar `/guidelines-review` antes de abrir un PR y `/docs-alignment` al cerrar una fase, antes de un pase a producción y cada vez que cambie una decisión; commits, PRs y comentarios de revisión citan las reglas por su ID.

## Modo de trabajo autónomo (vigente desde 2026-11-22)

- **Autonomía:** trabajar de forma autónoma y profesional: investigar (incluida la web), hacer pruebas de concepto, iterar y decidir sin preguntar lo que tenga una respuesta técnica defendible. Preguntar solo lo que sea de negocio o irreversible. Ir más allá de lo pedido cuando mejore el producto.
- **Ambición:** se busca un diseño de referencia, no la solución mínima. Las decisiones previas (ADR, contrato, guías) no son un techo: se pueden superar con argumentos y evidencia (spike, prueba, medición), dejando constancia del motivo en el commit o en Confluence.
- **Trabajo local:** se trabaja principalmente en local, en ramas propias, sin abrir tareas en Jira por cada paso. La clave TAR se asigna antes de abrir el PR. No se hace push ni PR sin pedirlo.
- **Lo que no cambia:** la puerta de calidad (`mvn -B verify`) pasa siempre; código con imports explícitos (sin `*` ni nombres totalmente calificados); la deuda técnica que se encuentre se corrige en el momento y se protege con una regla automática (Checkstyle, ArchUnit o CI) para que no vuelva.
