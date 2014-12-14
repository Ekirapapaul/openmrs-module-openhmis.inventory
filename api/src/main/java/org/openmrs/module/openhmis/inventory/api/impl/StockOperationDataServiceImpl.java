/*
 * The contents of this file are subject to the OpenMRS Public License
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.openhmis.inventory.api.impl;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.hibernate.Criteria;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Property;
import org.hibernate.criterion.Restrictions;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIException;
import org.openmrs.module.openhmis.commons.api.CustomizedOrderBy;
import org.openmrs.module.openhmis.commons.api.PagingInfo;
import org.openmrs.module.openhmis.commons.api.Utility;
import org.openmrs.module.openhmis.commons.api.entity.impl.BaseCustomizableMetadataDataServiceImpl;
import org.openmrs.module.openhmis.commons.api.f.Action1;
import org.openmrs.module.openhmis.inventory.api.IStockOperationDataService;
import org.openmrs.module.openhmis.inventory.api.model.IStockOperationType;
import org.openmrs.module.openhmis.inventory.api.model.StockOperation;
import org.openmrs.module.openhmis.inventory.api.model.StockOperationItem;
import org.openmrs.module.openhmis.inventory.api.model.StockOperationStatus;
import org.openmrs.module.openhmis.inventory.api.model.Stockroom;
import org.openmrs.module.openhmis.inventory.api.search.StockOperationSearch;
import org.openmrs.module.openhmis.inventory.api.security.BasicMetadataAuthorizationPrivileges;

public class StockOperationDataServiceImpl
		extends BaseCustomizableMetadataDataServiceImpl<StockOperation>
		implements IStockOperationDataService {
	private static final int MAX_OPERATION_NUMBER_LENGTH = 255;

	@Override
	protected BasicMetadataAuthorizationPrivileges getPrivileges() {
		return new BasicMetadataAuthorizationPrivileges();
	}

	@Override
	protected void validate(StockOperation operation) {
		StockOperationServiceImpl.validateOperation(operation);
	}

	@Override
	protected Order[] getDefaultSort() {
		// Return operations ordered by creation date, desc
		return new Order[] { Order.desc("dateCreated") };
	}

	@Override
	public StockOperation getOperationByNumber(String number) {
		if (StringUtils.isEmpty(number)) {
			throw new IllegalArgumentException("The operation number to find must be defined.");
		}
		if (number.length() > MAX_OPERATION_NUMBER_LENGTH) {
			throw new IllegalArgumentException("The operation number must be less than 256 characters.");
		}

		Criteria criteria = getRepository().createCriteria(getEntityClass());
		criteria.add(Restrictions.eq("operationNumber", number));

		return getRepository().selectSingle(getEntityClass(), criteria);
	}

	@Override
	public List<StockOperation> getOperationsByRoom(final Stockroom stockroom, PagingInfo paging) {
		if (stockroom == null) {
			throw new IllegalArgumentException("The stockroom must be defined.");
		}

		return executeCriteria(StockOperation.class, paging, new Action1<Criteria>() {
			@Override
			public void apply(Criteria criteria) {
				criteria.add(Restrictions.or(
						Restrictions.eq("source", stockroom),
						Restrictions.eq("destination", stockroom)
				));
			}
		}, getDefaultSort());
	}

	@Override
	public List<StockOperationItem> getItemsByOperation(final StockOperation operation, PagingInfo paging) {
		if (operation == null) {
			throw new IllegalArgumentException("The operation must be defined.");
		}

		return executeCriteria(StockOperationItem.class, paging, new Action1<Criteria>() {
			@Override
			public void apply(Criteria criteria) {
				criteria.add(Restrictions.eq("operation", operation));
				criteria.createCriteria("item", "i");
			}
		}, Order.asc("i.name"));
	}

	@Override
	public List<StockOperation> getUserOperations(User user, PagingInfo paging) {
		return getUserOperations(user, null, paging);
	}

	@Override
	public List<StockOperation> getUserOperations(final User user, final StockOperationStatus status, PagingInfo paging) {
		if (user == null) {
			throw new IllegalArgumentException("The user must be defined.");
		}

		// Get all the roles for this user (this traverses the role relationships to get any parent roles)
		final Set<Role> roles = user.getAllRoles();

		return executeCriteria(StockOperation.class, paging, new Action1<Criteria>() {
					@Override
					public void apply(Criteria criteria) {
						DetachedCriteria subQuery = DetachedCriteria.forClass(IStockOperationType.class);
						subQuery.setProjection(Property.forName("id"));

						// Add user/role filter
						if (roles != null && roles.size() > 0) {
							subQuery.add(Restrictions.or(
									// Types that require user approval
									Restrictions.eq("user", user),
									// Types that require role approval
									Restrictions.in("role", roles)
							));
						} else {
							// Types that require user approval
							subQuery.add(Restrictions.eq("user", user));
						}

						if (status != null) {
							criteria.add(Restrictions.and(
									Restrictions.eq("status", status),
									Restrictions.or(
											// Transactions created by the user
											Restrictions.eq("creator", user),
											Property.forName("instanceType").in(subQuery)
									)
							));
						} else {
							criteria.add(Restrictions.or(
											// Transactions created by the user
											Restrictions.eq("creator", user),
											Property.forName("instanceType").in(subQuery)
									)
							);
						}
					}
				}, Order.desc("dateCreated")
		);
	}

	@Override
	public List<StockOperation> getOperations(StockOperationSearch search) {
		return getOperations(search, null);
	}

	@Override
	public List<StockOperation> getOperations(final StockOperationSearch search, PagingInfo paging) {
		if (search == null) {
			throw new IllegalArgumentException("The operation search must be defined.");
		} else if (search.getTemplate() == null) {
			throw new IllegalArgumentException("The operation search template must be defined.");
		}

		return executeCriteria(StockOperation.class, paging, new Action1<Criteria>() {
			@Override
			public void apply(Criteria criteria) {
				search.updateCriteria(criteria);
			}
		}, getDefaultSort());
	}

	@Override
	public List<StockOperation> getOperationsSince(final Date operationDate, PagingInfo paging) {
		if (operationDate == null) {
			throw new IllegalArgumentException("The operation date must be defined.");
		}

		return executeCriteria(StockOperation.class, paging, new Action1<Criteria>() {
			@Override
			public void apply(Criteria criteria) {
				criteria.add(Restrictions.gt("operationDate", operationDate));
			}
		}, Order.asc("operationDate"));
	}

	@Override
	public List<StockOperation> getFutureOperations(final StockOperation operation, PagingInfo paging) {
		if (operation == null) {
			throw new IllegalArgumentException("The operation must be defined.");
		}

		return executeCriteria(StockOperation.class, paging, new Action1<Criteria>() {
			@Override
			public void apply(Criteria criteria) {
				criteria.add(Restrictions.or(
						Restrictions.and(
							createDateRestriction(operation.getOperationDate()),
							Restrictions.gt("operationOrder", operation.getOperationOrder())
						),
						Restrictions.gt("operationDate", operation.getOperationDate())
				));
			// Note that this ordering may not support all databases
			}
		}, CustomizedOrderBy.asc("convert(operation_date, date)"), Order.asc("operationOrder"), Order.asc
				("operationDate"));
	}

	@Override
	public List<StockOperation> getOperationsByDate(final Date date, PagingInfo paging) {
		return getOperationsByDate(date, paging, null, Order.asc("operationOrder"), Order.asc("operationDate"));
	}

	@Override
	public StockOperation getLastOperationByDate(final Date date) {
		List<StockOperation> results = getOperationsByDate(date, null, 1, Order.desc("operationOrder"),
				Order.desc("dateCreated"));

		if (results == null || results.size() == 0) {
			return null;
		} else {
			return results.get(0);
		}
	}

	@Override
	public StockOperation getFirstOperationByDate(final Date date) {
		List<StockOperation> results = getOperationsByDate(date, null, 1, Order.asc("operationOrder"),
				Order.asc("dateCreated"));

		if (results == null || results.size() == 0) {
			return null;
		} else {
			return results.get(0);
		}
	}

	private List<StockOperation> getOperationsByDate(final Date date, PagingInfo paging, final Integer maxResults,
			Order... orders) {
		if (date == null) {
			throw new IllegalArgumentException("The date to search for must be defined.");
		}

		return executeCriteria(StockOperation.class, paging, new Action1<Criteria>() {
			@Override
			public void apply(Criteria criteria) {
				criteria.add(createDateRestriction(date));
				if (maxResults != null && maxResults > 0) {
					criteria.setMaxResults(maxResults);
				}
			}
		}, orders);
	}

	private Criterion createDateRestriction(Date date) {
		Calendar cal = Calendar.getInstance();
		cal.setTime(date);
		Utility.clearCalendarTime(cal);
		final Date start = cal.getTime();

		cal.add(Calendar.DAY_OF_MONTH, 1);
		cal.add(Calendar.MILLISECOND, -1);
		final Date end = cal.getTime();

		return Restrictions.between("operationDate", start, end);
	}

	@Override
	public void purge(StockOperation operation) {
		if (operation != null && (
				(operation.hasReservedTransactions()) || operation.hasTransactions())
			) {
			throw new APIException("Stock operations can not be deleted if there are any associated transactions.");
		}

		super.purge(operation);
	}
}

